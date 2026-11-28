package com.chilsuitjo.back.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.payment.entity.Payment;
import com.chilsuitjo.back.payment.entity.PaymentVersion;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.payment.rule.PaymentResponse;
import com.chilsuitjo.back.payment.rule.SimilarPayment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지불 조회 쿼리(05 2-5). 기준 금액은 확인한 판·ACK·상대 당사자 제외로, 돈 있음은 확인 전도 포함해, 중복 후보는 지금 판의 방향·금액으로 찾는지 본다. 다른
 * 호실의 수리 건이면 비는지도 본다. 테스트는 끝나면 되돌린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
@Transactional
@ActiveProfiles("test")
class PaymentRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
  private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 27);

  @Autowired private PaymentRepository payments;
  @Autowired private PaymentVersionRepository versions;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void region() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동') ON CONFLICT DO NOTHING");
  }

  @Test
  void 기준_금액은_확인한_지불만_더하고_상대_당사자에게_준_돈은_뺀다() {
    UUID room = room();
    UUID repair = repair(room);
    record(room, repair, PayeeKind.VENDOR, 82_000).confirmCurrent();
    record(room, repair, PayeeKind.COUNTERPART, 30_000).confirmCurrent();
    record(room, repair, PayeeKind.VENDOR, 10_000);
    record(room, repair, PayeeKind.VENDOR, 5_000).changeResponse(PaymentResponse.DISPUTE);

    assertThat(payments.sumConfirmed(room, repair)).isEqualTo(82_000);
  }

  @Test
  void 기준_금액은_지금_판이_아니라_같이_확인한_판의_금액이다() {
    UUID room = room();
    UUID repair = repair(room);
    Payment payment = record(room, repair, PayeeKind.VENDOR, 50_000);
    payment.confirmCurrent();
    payments.flush();
    UUID second = Uuids.v7();
    versions.saveAndFlush(
        version(
            second,
            room,
            payment.getId(),
            2,
            PayeeKind.VENDOR,
            70_000,
            payment.getCurrentVersionId()));
    jdbc.update("UPDATE payment SET current_version_id = ? WHERE id = ?", second, payment.getId());

    assertThat(payments.sumConfirmed(room, repair)).isEqualTo(50_000);
  }

  @Test
  void 확인_전_지불도_돈_있음으로_센다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID empty = repair(room);
    record(room, repair, PayeeKind.VENDOR, 82_000);

    assertThat(payments.existsByRoomRecordIdAndRepairCaseId(room, repair)).isTrue();
    assertThat(payments.existsByRoomRecordIdAndRepairCaseId(room, empty)).isFalse();
  }

  @Test
  void 다른_호실의_수리_건이면_돈_없음이고_기준_금액은_0이다() {
    UUID room = room();
    UUID other = room();
    UUID repair = repair(room);
    record(room, repair, PayeeKind.VENDOR, 82_000).confirmCurrent();
    payments.flush();

    assertThat(payments.existsByRoomRecordIdAndRepairCaseId(other, repair)).isFalse();
    assertThat(payments.sumConfirmed(other, repair)).isZero();
    assertThat(
            payments.findSameDirectionAndAmount(
                other, repair, PayerKind.TENANT, PayeeKind.VENDOR, 82_000))
        .isEmpty();
  }

  @Test
  void 중복_후보는_같은_방향_같은_금액만_기록_순서로_찾는다() {
    UUID room = room();
    UUID repair = repair(room);
    Payment first = record(room, repair, PayeeKind.VENDOR, 82_000);
    record(room, repair, PayeeKind.COUNTERPART, 82_000);
    record(room, repair, PayeeKind.VENDOR, 80_000);
    Payment second = record(room, repair, PayeeKind.VENDOR, 82_000);
    second.confirmCurrent();
    payments.flush();

    assertThat(
            payments.findSameDirectionAndAmount(
                room, repair, PayerKind.TENANT, PayeeKind.VENDOR, 82_000))
        .containsExactly(
            new SimilarPayment(
                first.getId(), PartySide.TENANT, PaymentResponse.PENDING, PAID_ON, NOW),
            new SimilarPayment(
                second.getId(), PartySide.TENANT, PaymentResponse.ACK, PAID_ON, NOW));
  }

  @Test
  void 지불_ID는_이_수리_건의_것만_기록_순서로_찾는다() {
    UUID room = room();
    UUID other = room();
    UUID repair = repair(room);
    UUID another = repair(room);
    Payment first = record(room, repair, PayeeKind.VENDOR, 82_000);
    record(room, another, PayeeKind.VENDOR, 10_000);
    Payment second = record(room, repair, PayeeKind.COUNTERPART, 30_000);
    second.changeResponse(PaymentResponse.DISPUTE);
    payments.flush();

    assertThat(payments.findIds(room, repair)).containsExactly(first.getId(), second.getId());
    assertThat(payments.findIds(other, repair)).isEmpty();
  }

  /** 세입자가 남긴 첫 판 지불. 머리를 먼저 내보내야 판의 FK(즉시 검사)가 통과한다. 지금 판 FK는 지연이라 판보다 먼저 들어가도 된다. */
  private Payment record(UUID room, UUID repair, PayeeKind payee, long amount) {
    UUID id = Uuids.v7();
    UUID versionId = Uuids.v7();
    Payment payment = payments.saveAndFlush(new Payment(id, room, repair, versionId, false, NOW));
    versions.saveAndFlush(version(versionId, room, id, 1, payee, amount, null));
    return payment;
  }

  private static PaymentVersion version(
      UUID id, UUID room, UUID paymentId, int no, PayeeKind payee, long amount, UUID previousId) {
    return new PaymentVersion(
        id,
        room,
        paymentId,
        no,
        PayerKind.TENANT,
        null,
        payee,
        null,
        amount,
        PAID_ON,
        PartySide.TENANT,
        null,
        previousId,
        NOW);
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private UUID repair(UUID room) {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO repair_case (id, room_record_id, current_content_id, status, defect_ack,
          money_status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        content);
    jdbc.update(
        """
        INSERT INTO repair_content (id, room_record_id, repair_case_id, no, room_part_id, title,
          created_by_side, created_at)
        VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
        """,
        content,
        room,
        id,
        part);
    return id;
  }
}
