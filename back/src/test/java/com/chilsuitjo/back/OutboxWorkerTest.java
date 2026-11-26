package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.FakeRecipientDirectory;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.OutboxPoller;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 최소판(04 4-2): 행동 트랜잭션 안에서 넣고, 커밋된 뒤 작업자가 꺼내 보낸다. {@code SKIP LOCKED}는 진짜 PostgreSQL에서만 확인되므로
 * Testcontainers로 돌리고, 작업자가 따로 커밋해야 해서 테스트 트랜잭션으로 감싸지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
class OutboxWorkerTest {

  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private OutboxWorker worker;
  @Autowired private FakeSmsGateway gateway;
  @Autowired private FakeRecipientDirectory recipients;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private NoticeRepository noticeRepository;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    gateway.reset();
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999998', '테스트', '아웃박스동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999998', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    party = party("LANDLORD");
    recipients.seedParty(party, "01012345678");
  }

  /** 받는 자리. 통지는 같은 호실의 자리만 가리킬 수 있다(V1 {@code fk_notice_recipient_party_id_room}). */
  private UUID party(String side) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, ?, 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side);
    return id;
  }

  @Test
  void 트랜잭션_밖에서_넣으면_거절한다() {
    assertThatThrownBy(() -> noticeOutbox.enqueueSms(command()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void 넣은_통지와_문자_줄은_같은_트랜잭션이라_롤백되면_함께_사라진다() {
    UUID[] noticeId = new UUID[1];
    transaction.executeWithoutResult(status -> {
      noticeId[0] = noticeOutbox.enqueueSms(command());
      status.setRollbackOnly();
    });

    assertThat(count("notice", "id = ?", noticeId[0])).isZero();
    assertThat(count("outbox", "dedupe_key = ?", "sms:" + noticeId[0])).isZero();
  }

  @Test
  void 문자_줄에는_통지_ID만_있고_번호는_없다() {
    UUID noticeId = enqueue();

    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE dedupe_key = ?",
            String.class,
            "sms:" + noticeId);

    assertThat(payload).contains(noticeId.toString()).doesNotContain("01012345678");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload->>'noticeId' FROM outbox WHERE dedupe_key = ?",
                String.class,
                "sms:" + noticeId))
        .isEqualTo(noticeId.toString());
  }

  @Test
  void 작업자가_보내면_통지는_SENT가_되고_줄은_DONE이다() {
    UUID noticeId = enqueue();

    worker.runOnce();

    Map<String, Object> notice = notice(noticeId);
    assertThat(notice.get("status")).isEqualTo("SENT");
    assertThat(notice.get("provider_message_id")).isEqualTo("fake-" + noticeId);
    assertThat(notice.get("batch_lead_id")).isEqualTo(noticeId);
    assertThat(notice.get("sent_at")).isNotNull();
    assertThat(notice.get("next_poll_at")).isNotNull();
    assertThat(outboxStatus(noticeId)).isEqualTo("DONE");
    assertThat(gateway.sentBatchIds()).containsExactly(noticeId);
  }

  @Test
  void 한_번_보낸_줄은_다시_돌아도_또_보내지_않는다() {
    UUID noticeId = enqueue();

    worker.runOnce();
    worker.runOnce();

    assertThat(gateway.sentBatchIds()).containsExactly(noticeId);
  }

  @Test
  void 결과를_모르면_보낸_것으로_보고_다시_보내지_않는다() {
    gateway.respondWith(SmsSendResult.Outcome.UNKNOWN);
    UUID noticeId = enqueue();

    worker.runOnce();
    worker.runOnce();

    assertThat(notice(noticeId).get("status")).isEqualTo("SENT");
    assertThat(notice(noticeId).get("provider_message_id")).isNull();
    assertThat(gateway.sentBatchIds()).containsExactly(noticeId);
  }

  @Test
  void 업체가_거절하면_통지는_FAILED다() {
    gateway.respondWith(SmsSendResult.Outcome.REJECTED);
    UUID noticeId = enqueue();

    worker.runOnce();

    assertThat(notice(noticeId).get("status")).isEqualTo("FAILED");
    assertThat(outboxStatus(noticeId)).isEqualTo("DONE");
  }

  @Test
  void 업체에_닿지_않으면_나중에_다시_보내려고_PENDING으로_둔다() {
    gateway.respondWith(SmsSendResult.Outcome.RETRY);
    UUID noticeId = enqueue();

    worker.runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox WHERE dedupe_key = ?",
                Integer.class,
                "sms:" + noticeId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT next_attempt_at > CURRENT_TIMESTAMP FROM outbox WHERE dedupe_key = ?",
                Boolean.class,
                "sms:" + noticeId))
        .isTrue();
    assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
  }

  @Test
  void 업체에_닿지_못한_채_3일이_지나면_최종_실패다() {
    gateway.respondWith(SmsSendResult.Outcome.RETRY);
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' WHERE dedupe_key = ?",
        "sms:" + noticeId);

    worker.runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("FAILED");
    assertThat(notice(noticeId).get("status")).isEqualTo("FAILED");
  }

  @Test
  void 수신자_조회_예외가_최종_실패하면_통지와_아웃박스가_함께_실패한다() {
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' WHERE dedupe_key = ?",
        "sms:" + noticeId);
    RecipientDirectory failingRecipients = mock(RecipientDirectory.class);
    when(failingRecipients.phoneOfRecipient(party)).thenThrow(new IllegalStateException("조회 실패"));
    OutboxWorker failingWorker = workerWith(failingRecipients, noticeRepository);

    failingWorker.runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("FAILED");
    assertThat(notice(noticeId).get("status")).isEqualTo("FAILED");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @Test
  void 수신자_조회_예외는_기한_전이면_다시_시도한다() {
    UUID noticeId = enqueue();
    RecipientDirectory failingRecipients = mock(RecipientDirectory.class);
    when(failingRecipients.phoneOfRecipient(party)).thenThrow(new IllegalStateException("조회 실패"));

    workerWith(failingRecipients, noticeRepository).runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("PENDING");
    assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @Test
  void 통지_조회_예외_뒤_결과_저장때_다시_찾아_함께_실패한다() {
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' WHERE dedupe_key = ?",
        "sms:" + noticeId);
    NoticeRepository failingOnce = mock(NoticeRepository.class, delegatesTo(noticeRepository));
    doThrow(new IllegalStateException("일시적 조회 실패")).when(failingOnce).findById(noticeId);

    workerWith(recipients, failingOnce).runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("FAILED");
    assertThat(notice(noticeId).get("status")).isEqualTo("FAILED");
  }

  @Test
  void 결과_저장_중_통지_잠금이_실패하면_부분_커밋하지_않는다() {
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' WHERE dedupe_key = ?",
        "sms:" + noticeId);
    RecipientDirectory failingRecipients = mock(RecipientDirectory.class);
    when(failingRecipients.phoneOfRecipient(party)).thenThrow(new IllegalStateException("조회 실패"));
    NoticeRepository failingLock = mock(NoticeRepository.class, delegatesTo(noticeRepository));
    doThrow(new IllegalStateException("DB 오류")).when(failingLock).findForUpdate(noticeId);

    workerWith(failingRecipients, failingLock).runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("SENDING");
    assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @Test
  void 결과_저장_DB_제약이_실패하면_두_상태_변경이_되돌아간다() {
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' WHERE dedupe_key = ?",
        "sms:" + noticeId);
    RecipientDirectory failingRecipients = mock(RecipientDirectory.class);
    when(failingRecipients.phoneOfRecipient(party)).thenThrow(new IllegalStateException("조회 실패"));
    jdbc.execute(
        "ALTER TABLE state.notice ADD CONSTRAINT test_reject_failed CHECK (status <> 'FAILED') NOT VALID");
    try {
      workerWith(failingRecipients, noticeRepository).runOnce();
      assertThat(outboxStatus(noticeId)).isEqualTo("SENDING");
      assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
      assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
    } finally {
      jdbc.execute("ALTER TABLE state.notice DROP CONSTRAINT test_reject_failed");
    }
  }

  @Test
  void 통지가_실제로_없으면_발송하지_않고_작업을_끝낸다() {
    UUID noticeId = enqueue();
    jdbc.update("DELETE FROM notice WHERE id = ?", noticeId);

    worker.runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("DONE");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"noticeId\":null}",
        "{\"noticeId\":123}",
        "{\"noticeId\":\"invalid\"}",
        "{\"noticeId\":\"------------------------------------\"}",
        "{\"nested\":{\"noticeId\":\"00000000-0000-0000-0000-000000000001\"}}",
        "[]"
      })
  void 잘못된_문자_payload는_실패하고_발송하지_않는다(String payload) {
    UUID noticeId = enqueue();
    jdbc.update(
        "UPDATE state.outbox SET payload = CAST(? AS jsonb) WHERE dedupe_key = ?",
        payload,
        "sms:" + noticeId);

    worker.runOnce();
    worker.runOnce();

    assertThat(outboxStatus(noticeId)).isEqualTo("FAILED");
    assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @Test
  void 다른_통지를_가리키는_payload는_발송하지_않는다() {
    UUID original = enqueue();
    UUID other = enqueue();
    jdbc.update("UPDATE state.outbox SET status = 'DONE' WHERE dedupe_key = ?", "sms:" + other);
    jdbc.update(
        "UPDATE state.outbox SET payload = CAST(? AS jsonb) WHERE dedupe_key = ?",
        "{\"noticeId\":\"" + other + "\"}",
        "sms:" + original);

    worker.runOnce();

    assertThat(outboxStatus(original)).isEqualTo("FAILED");
    assertThat(notice(original).get("status")).isEqualTo("REQUESTED");
    assertThat(notice(other).get("status")).isEqualTo("REQUESTED");
    assertThat(gateway.sentBatchIds()).isEmpty();
  }

  @Test
  void 취소한_줄은_작업자가_가져가지_않고_통지도_그대로다() {
    UUID cancelled = enqueue();
    UUID pending = enqueue();
    // 취소 서비스는 아직 없어서(#100 U2 2단계) 두 상태를 직접 바꾼다.
    jdbc.update(
        "UPDATE state.outbox SET status = 'CANCELLED' WHERE dedupe_key = ?", "sms:" + cancelled);
    jdbc.update("UPDATE state.notice SET status = 'CANCELLED' WHERE id = ?", cancelled);

    worker.runOnce();
    worker.runOnce();

    assertThat(outboxStatus(cancelled)).isEqualTo("CANCELLED");
    assertThat(notice(cancelled).get("status")).isEqualTo("CANCELLED");
    assertThat(gateway.sentBatchIds()).doesNotContain(cancelled).contains(pending);
  }

  private OutboxWorker workerWith(RecipientDirectory directory, NoticeRepository repository) {
    return new OutboxWorker(
        outboxRepository,
        repository,
        inviteLinks,
        directory,
        gateway,
        clock,
        transactionManager,
        50,
        List.of(Duration.ofMinutes(1)),
        Duration.ofDays(3));
  }

  @Test
  void 보낼_번호가_없으면_보내지_않고_통지를_최종_실패로_두지_않는다() {
    UUID noticeId =
        transaction.execute(status ->
            noticeOutbox.enqueueSms(
                new SmsNoticeCommand(room, party("TENANT"), NoticeKind.REQUEST, null, null)));

    worker.runOnce();

    assertThat(notice(noticeId).get("status")).isEqualTo("REQUESTED");
    assertThat(outboxStatus(noticeId)).isEqualTo("FAILED");
    assertThat(gateway.sentBatchIds()).doesNotContain(noticeId);
  }

  @Test
  void 발송_중에는_나머지_문자가_PENDING으로_남는다() throws Exception {
    UUID first = enqueue();
    UUID second = enqueue();
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    OutboxWorker blockedWorker =
        new OutboxWorker(
            outboxRepository,
            noticeRepository,
            inviteLinks,
            recipients,
            message -> {
              sending.countDown();
              awaitThen(release, () -> {});
              return gateway.send(message);
            },
            clock,
            transactionManager,
            50,
            List.of(Duration.ofMinutes(1)),
            Duration.ofDays(3));
    CompletableFuture<Integer> result = CompletableFuture.supplyAsync(blockedWorker::runOnce);
    try {
      assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(List.of(outboxStatus(first), outboxStatus(second)))
          .containsExactlyInAnyOrder("SENDING", "PENDING");
    } finally {
      release.countDown();
      result.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void 작업자_둘이_동시에_돌아도_같은_줄을_두_번_보내지_않는다() throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      ids.add(enqueue());
    }
    CountDownLatch start = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(() -> awaitThen(start, worker::runOnce));
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(() -> awaitThen(start, worker::runOnce));
    start.countDown();
    CompletableFuture.allOf(first, second).get();
    worker.runOnce();

    assertThat(gateway.sentBatchIds()).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void 작업자를_끄면_예약_실행_부품이_없다() {
    assertThat(context.getBeanNamesForType(OutboxPoller.class)).isEmpty();
  }

  private SmsNoticeCommand command() {
    return new SmsNoticeCommand(room, party, NoticeKind.REQUEST, null, null);
  }

  private UUID enqueue() {
    return transaction.execute(status -> noticeOutbox.enqueueSms(command()));
  }

  private Map<String, Object> notice(UUID id) {
    return jdbc.queryForMap("SELECT * FROM notice WHERE id = ?", id);
  }

  private String outboxStatus(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE dedupe_key = ?", String.class, "sms:" + noticeId);
  }

  private int count(String table, String where, Object arg) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, arg);
  }

  private static void awaitThen(CountDownLatch latch, Runnable action) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    action.run();
  }
}
