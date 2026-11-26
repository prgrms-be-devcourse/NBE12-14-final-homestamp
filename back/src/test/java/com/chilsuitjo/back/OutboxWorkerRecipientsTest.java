package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 작업자가 room 실제 번호 조회({@code JpaRecipientDirectory})로 보내는지 본다(#163). 다른 작업자 테스트는 test 가짜를 쓰므로,
 * 실제 구현에서 "번호가 있으면 보냄, 받을 사람이 없으면 보내지 않고 끝냄"이 이어지는지는 여기서만 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.outbox.worker.enabled=false")
@ActiveProfiles("test")
class OutboxWorkerRecipientsTest {

  private static final String REGION = "9799999901";

  @Autowired private OutboxWorker worker;
  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private FakeSmsGateway gateway;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID room;

  @BeforeEach
  void setUp() {
    gateway.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    room = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at,"
            + " version) VALUES (?, ?, '302호', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        room,
        REGION);
  }

  @Test
  void 초대_자리에_번호가_있으면_보낸다() {
    UUID party = seat("INVITED", newPhone());
    UUID notice = enqueue(party);

    drain(notice);

    assertThat(gateway.sentBatchIds()).contains(notice);
    assertThat(outboxStatus(notice)).isEqualTo("DONE");
  }

  @Test
  void 거절한_자리는_번호가_남아_있어도_보내지_않고_끝낸다() {
    UUID party = seat("REJECTED", newPhone());
    UUID notice = enqueue(party);

    drain(notice);

    assertThat(gateway.sentBatchIds()).doesNotContain(notice);
    assertThat(outboxStatus(notice)).isEqualTo("FAILED");
    // 업체 실패가 아니라 통지는 최종 실패로 두지 않는다(OutboxWorker.skipWithoutPhone)
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM state.notice WHERE id = ?", String.class, notice))
        .isEqualTo("REQUESTED");
  }

  private UUID enqueue(UUID party) {
    return new TransactionTemplate(transactionManager)
        .execute(status ->
            noticeOutbox.enqueueSms(
                new SmsNoticeCommand(room, party, NoticeKind.INVITE, null, null)));
  }

  /** 다른 테스트가 남긴 줄이 있어도 이 통지의 줄이 처리될 때까지 돌린다. */
  private void drain(UUID notice) {
    for (int i = 0; i < 10 && "PENDING".equals(outboxStatus(notice)); i++) {
      worker.runOnce();
    }
  }

  private String outboxStatus(UUID notice) {
    return jdbc.queryForObject(
        "SELECT status FROM state.outbox WHERE dedupe_key = ?", String.class, "sms:" + notice);
  }

  private UUID seat(String status, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', ?, ?, CURRENT_TIMESTAMP, 0)",
        id,
        room,
        invitePhone,
        status);
    return id;
  }

  private static String newPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
