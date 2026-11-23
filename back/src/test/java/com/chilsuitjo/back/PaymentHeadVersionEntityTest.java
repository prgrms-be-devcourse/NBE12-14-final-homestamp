package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.payment.entity.Payment;
import com.chilsuitjo.back.payment.entity.PaymentVersion;
import com.chilsuitjo.back.payment.rule.ConfirmStrength;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.payment.rule.PaymentResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/**
 * 지불(머리)과 첫 판은 서로의 ID가 필요하다. 앱이 두 ID를 먼저 만들어 한 트랜잭션에 저장할 수 있는지 본다(05 0장 {@code id}, #109).
 *
 * <p>테스트는 끝나면 되돌리므로, 커밋 때 하는 지연 FK 검사는 {@code SET CONSTRAINTS ALL IMMEDIATE}로 그 자리에서 당겨 한다. 판의
 * {@code payment_id} FK는 지연이 아니라서 머리를 먼저 저장해야 한다.
 */
class PaymentHeadVersionEntityTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 앱이_만든_ID로_지불과_첫_판을_한_트랜잭션에_저장한다() {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    Payment payment = new Payment(paymentId, room, repair(room), versionId, true, NOW);
    PaymentVersion version = firstVersion(versionId, room, paymentId);

    // save가 merge로 가면 복사본을 돌려준다. 같은 객체면 SELECT 없이 persist(INSERT 한 번)로 갔다.
    Payment savedPayment = new SimpleJpaRepository<>(Payment.class, entityManager).save(payment);
    PaymentVersion savedVersion =
        new SimpleJpaRepository<>(PaymentVersion.class, entityManager).save(version);
    entityManager.flush();

    assertThat(savedPayment).isSameAs(payment);
    assertThat(savedVersion).isSameAs(version);
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));

    entityManager.clear();
    Payment loaded = entityManager.find(Payment.class, paymentId);
    assertThat(loaded.getCurrentVersionId()).isEqualTo(versionId);
    assertThat(loaded.getConfirmStrength()).isEqualTo(ConfirmStrength.EVIDENCE);
    assertThat(loaded.getResponse()).isEqualTo(PaymentResponse.PENDING);
    assertThat(loaded.getVersion()).isZero();
    PaymentVersion loadedVersion = entityManager.find(PaymentVersion.class, versionId);
    assertThat(loadedVersion.getPayerKind()).isEqualTo(PayerKind.THIRD_PARTY);
    assertThat(loadedVersion.getPayerLabel()).isEqualTo("가족");
    assertThat(loadedVersion.getAmount()).isEqualTo(82_000);
    assertThat(loadedVersion.getPaidOn()).isEqualTo(LocalDate.of(2026, 11, 22));
  }

  @Test
  void 첫_판_없이_지불만_저장하면_커밋_때_지연_FK가_거절한다() {
    UUID room = room();
    entityManager.persist(new Payment(Uuids.v7(), room, repair(room), Uuids.v7(), false, NOW));
    entityManager.flush();

    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    assertThat(error.getMessage()).contains("fk_payment_current_version");
  }

  @Test
  void 판을_머리보다_먼저_저장하면_판의_FK가_바로_거절한다() {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    UUID repairCase = repair(room);

    // 연관 매핑 없이 UUID 값만 두므로 Hibernate는 persist 순서대로 INSERT한다.
    entityManager.persist(firstVersion(versionId, room, paymentId));
    entityManager.persist(new Payment(paymentId, room, repairCase, versionId, false, NOW));

    var error = assertThrows(PersistenceException.class, () -> entityManager.flush());
    assertThat(error).hasStackTraceContaining("fk_payment_version_payment_id_room");
  }

  @Test
  void 저장한_뒤와_다시_읽은_판은_새_행이_아니다() {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    entityManager.persist(new Payment(paymentId, room, repair(room), versionId, false, NOW));
    PaymentVersion version = firstVersion(versionId, room, paymentId);
    assertThat(version.isNew()).isTrue();

    entityManager.persist(version);
    entityManager.flush();
    assertThat(version.isNew()).isFalse();

    entityManager.clear();
    assertThat(entityManager.find(PaymentVersion.class, versionId).isNew()).isFalse();
  }

  @Test
  void 두_번째_판은_첫_판을_가리키고_지불의_현재_판이_된다() {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID firstVersionId = Uuids.v7();
    UUID secondVersionId = Uuids.v7();
    entityManager.persist(new Payment(paymentId, room, repair(room), firstVersionId, false, NOW));
    entityManager.persist(firstVersion(firstVersionId, room, paymentId));
    entityManager.flush();

    entityManager.persist(
        new PaymentVersion(
            secondVersionId,
            room,
            paymentId,
            2,
            PayerKind.LANDLORD,
            null,
            PayeeKind.VENDOR,
            null,
            90_000,
            LocalDate.of(2026, 11, 23),
            PartySide.LANDLORD,
            null,
            firstVersionId,
            NOW.plusSeconds(1)));
    entityManager.flush();
    // 머리의 상태 변경 메서드는 MONEY-1~2에서 더하므로 현재 판 이동만 SQL로 준비한다.
    jdbc.update(
        "UPDATE payment SET current_version_id = ? WHERE id = ?", secondVersionId, paymentId);
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));

    entityManager.clear();
    assertThat(entityManager.find(Payment.class, paymentId).getCurrentVersionId())
        .isEqualTo(secondVersionId);
    PaymentVersion loaded = entityManager.find(PaymentVersion.class, secondVersionId);
    assertThat(loaded.getPaymentId()).isEqualTo(paymentId);
    assertThat(loaded.getRoomRecordId()).isEqualTo(room);
    assertThat(loaded.getNo()).isEqualTo(2);
    assertThat(loaded.getPreviousId()).isEqualTo(firstVersionId);
    assertThat(loaded.getAmount()).isEqualTo(90_000);
    PaymentVersion first = entityManager.find(PaymentVersion.class, firstVersionId);
    assertThat(first.getNo()).isEqualTo(1);
    assertThat(first.getPreviousId()).isNull();
    assertThat(first.getAmount()).isEqualTo(82_000);
  }

  @Test
  void 다른_호실의_수리_건을_가리키는_지불은_FK가_거절한다() {
    UUID room = room();
    UUID otherRepair = repair(room());
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    entityManager.persist(new Payment(paymentId, room, otherRepair, versionId, false, NOW));
    entityManager.persist(firstVersion(versionId, room, paymentId));

    var error = assertThrows(PersistenceException.class, () -> entityManager.flush());
    assertThat(error).hasStackTraceContaining("fk_payment_repair_case_id_room");
  }

  @ParameterizedTest
  @CsvSource({"ONE_SIDE, PENDING", "BOTH, ACK", "EVIDENCE, NEED_MORE", "ONE_SIDE, DISPUTE"})
  void 지불의_확인_강도와_응답을_DB에서_enum으로_읽는다(ConfirmStrength strength, PaymentResponse response) {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    entityManager.persist(new Payment(paymentId, room, repair(room), versionId, false, NOW));
    entityManager.persist(firstVersion(versionId, room, paymentId));
    entityManager.flush();

    // 응답 처리는 MONEY-2에서 구현한다. 여기서는 DB CHECK와 JPA enum 매핑을 검증한다.
    jdbc.update(
        """
        UPDATE payment SET confirm_strength = ?, response = ?, confirmed_version_id = ?
        WHERE id = ?
        """,
        strength.name(),
        response.name(),
        response == PaymentResponse.ACK ? versionId : null,
        paymentId);
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));

    entityManager.clear();
    Payment loaded = entityManager.find(Payment.class, paymentId);
    assertThat(loaded.getConfirmStrength()).isEqualTo(strength);
    assertThat(loaded.getResponse()).isEqualTo(response);
    assertThat(loaded.getConfirmedVersionId())
        .isEqualTo(response == PaymentResponse.ACK ? versionId : null);
  }

  @ParameterizedTest
  @CsvSource({
    "TENANT, VENDOR, TENANT",
    "LANDLORD, COUNTERPART, LANDLORD",
    "THIRD_PARTY, THIRD_PARTY, TENANT"
  })
  void 지불_판의_낸_사람과_받는_사람과_기록한_측을_저장하고_읽는다(
      PayerKind payerKind, PayeeKind payeeKind, PartySide recordedBySide) {
    UUID room = room();
    UUID paymentId = Uuids.v7();
    UUID versionId = Uuids.v7();
    String payerLabel = payerKind == PayerKind.THIRD_PARTY ? "가족" : null;
    String payeeLabel = payeeKind == PayeeKind.THIRD_PARTY ? "윗집" : null;
    entityManager.persist(new Payment(paymentId, room, repair(room), versionId, false, NOW));
    entityManager.persist(
        new PaymentVersion(
            versionId,
            room,
            paymentId,
            1,
            payerKind,
            payerLabel,
            payeeKind,
            payeeLabel,
            82_000,
            LocalDate.of(2026, 11, 22),
            recordedBySide,
            null,
            null,
            NOW));
    entityManager.flush();
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));

    entityManager.clear();
    PaymentVersion loaded = entityManager.find(PaymentVersion.class, versionId);
    assertThat(loaded.getPayerKind()).isEqualTo(payerKind);
    assertThat(loaded.getPayerLabel()).isEqualTo(payerLabel);
    assertThat(loaded.getPayeeKind()).isEqualTo(payeeKind);
    assertThat(loaded.getPayeeLabel()).isEqualTo(payeeLabel);
    assertThat(loaded.getRecordedBySide()).isEqualTo(recordedBySide);
  }

  private PaymentVersion firstVersion(UUID versionId, UUID room, UUID paymentId) {
    return new PaymentVersion(
        versionId,
        room,
        paymentId,
        1,
        PayerKind.THIRD_PARTY,
        "가족",
        PayeeKind.VENDOR,
        null,
        82_000,
        LocalDate.of(2026, 11, 22),
        PartySide.TENANT,
        "영수증",
        null,
        NOW);
  }
}
