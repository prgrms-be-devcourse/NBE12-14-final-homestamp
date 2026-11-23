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
    assertThat(loaded.getResponse()).isEqualTo(PaymentResponse.NONE);
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
