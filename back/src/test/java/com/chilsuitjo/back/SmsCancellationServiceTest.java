package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.InMemoryRecipientDirectory;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.OutboxWorker;
import com.chilsuitjo.back.notification.service.SmsCancellation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보내지 않은 문자 취소(#100 U2)를 실제 PostgreSQL에서 확인한다. 작업자와 엇갈리는 경우는 커밋된 행과 두 트랜잭션이 필요해서 테스트 트랜잭션으로 감싸지
 * 않는다. {@link OutboxWorkerTest}와 같은 설정이라 같은 컨텍스트(작업자 예약 실행 꺼짐)를 쓰고, 끝나면 남은 보낼 줄을 닫아 그 테스트에 섞이지 않게
 * 한다. 번호 조회는 {@link OutboxWorkerTest}와 같은 test 가짜({@link InMemoryRecipientDirectory})를 받는다(main 가짜는
 * 지움, #163).
 */
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.RecipientsConfig.class})
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SmsCancellationServiceTest {

  private static final Instant AT = Instant.parse("2026-11-26T01:00:00Z");

  @Autowired private SmsCancellation cancellation;
  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private OutboxWorker worker;
  @Autowired private FakeSmsGateway gateway;
  @Autowired private InMemoryRecipientDirectory recipients;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private DeliveryPollSchedule deliveryPollSchedule;
  @Autowired private ActionRequestContext requestContext;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private NoticeRepository noticeRepository;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> rooms = new ArrayList<>();
  private UUID member;
  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    gateway.reset();
    recipients.reset();
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999997', '테스트', '취소동')"
            + " ON CONFLICT DO NOTHING");
    member = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        member);
    room = room();
    party = party(room);
  }

  /** 이 테스트가 남긴 보낼 줄을 닫는다. 같은 DB를 쓰는 작업자 테스트가 가져가지 않게. */
  @AfterEach
  void closeLeftovers() {
    for (UUID r : rooms) {
      jdbc.update(
          """
          UPDATE outbox SET status = 'DONE'
          WHERE status IN ('PENDING', 'SENDING')
            AND (dedupe_key IN (SELECT 'sms:' || id FROM notice WHERE room_record_id = ?)
              OR payload ->> 'noticeId' IN (SELECT id::text FROM notice WHERE room_record_id = ?))
          """,
          r,
          r);
    }
  }

  // ---- 정상 취소 ----

  @Test
  void 보내기_전_문자는_통지와_문자_줄이_함께_취소된다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    Instant nextAttempt = nextAttemptAt(notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isEqualTo(1);

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(attempts(notice)).isZero();
    assertThat(nextAttemptAt(notice)).isEqualTo(nextAttempt);
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notice WHERE id = ?", notice);
    assertThat(row.get("sent_at")).isNull();
    assertThat(row.get("delivered_at")).isNull();
    assertThat(row.get("failed_at")).isNull();
  }

  @Test
  void 다시_시도_대기_중인_줄도_취소하고_시도_횟수와_다음_시각은_그대로다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    jdbc.update(
        "UPDATE outbox SET attempts = 2, next_attempt_at = CURRENT_TIMESTAMP + interval '1 hour'"
            + " WHERE dedupe_key = ?",
        "sms:" + notice);
    Instant nextAttempt = nextAttemptAt(notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isEqualTo(1);

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(attempts(notice)).isEqualTo(2);
    assertThat(nextAttemptAt(notice)).isEqualTo(nextAttempt);
  }

  @Test
  void 여러_링크를_한_번에_취소하고_돌려준_수는_실제로_바뀐_줄_수와_같다() {
    UUID pendingLink = link(room, party, false);
    UUID retryLink = link(room, party, true);
    UUID sendingLink = link(room, party, true);
    UUID doneLink = link(room, party, true);
    UUID pending = enqueue(room, party, pendingLink);
    UUID retry = enqueue(room, party, retryLink);
    UUID sending = enqueue(room, party, sendingLink);
    UUID done = enqueue(room, party, doneLink);
    jdbc.update(
        "UPDATE outbox SET attempts = 1, next_attempt_at = CURRENT_TIMESTAMP + interval '5 minutes'"
            + " WHERE dedupe_key = ?",
        "sms:" + retry);
    setOutbox(sending, "SENDING");
    setOutbox(done, "DONE");
    jdbc.update(
        "UPDATE notice SET status = 'SENT', sent_at = CURRENT_TIMESTAMP WHERE id = ?", done);

    int cancelled =
        inSave(() ->
            cancellation.cancelPendingSms(
                room, List.of(pendingLink, retryLink, sendingLink, doneLink)));

    assertThat(cancelled).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox WHERE status = 'CANCELLED'
                  AND dedupe_key IN (SELECT 'sms:' || id FROM notice WHERE room_record_id = ?)
                """,
                Integer.class,
                room))
        .isEqualTo(cancelled);
    assertThat(noticeStatus(pending)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(retry)).isEqualTo("CANCELLED");
    assertThat(outboxStatus(sending)).isEqualTo("SENDING");
    assertThat(noticeStatus(sending)).isEqualTo("REQUESTED");
    assertThat(outboxStatus(done)).isEqualTo("DONE");
    assertThat(noticeStatus(done)).isEqualTo("SENT");
  }

  @Test
  void 이미_무효인_링크를_가리키는_보내지_않은_문자도_취소한다() {
    UUID invalid = link(room, party, true);
    UUID notice = enqueue(room, party, invalid);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(invalid)))).isEqualTo(1);

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
  }

  // ---- 대상 밖 ----

  @Test
  void 다른_호실과_목록_밖_링크와_링크_없는_통지와_서비스_안_알림은_그대로다() {
    UUID target = link(room, party, false);
    UUID unlisted = link(room, party, false);
    UUID otherRoom = room();
    UUID otherParty = party(otherRoom);
    UUID otherLink = link(otherRoom, otherParty, false);
    UUID targetNotice = enqueue(room, party, target);
    UUID unlistedNotice = enqueue(room, party, unlisted);
    UUID noLinkNotice = enqueue(room, party, null);
    UUID otherRoomNotice = enqueue(otherRoom, otherParty, otherLink);
    UUID inApp = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, link_id, recipient_party_id, kind, channel, status,
          created_at, version)
        VALUES (?, ?, ?, ?, 'INVITE', 'IN_APP', 'REQUESTED', CURRENT_TIMESTAMP, 0)
        """,
        inApp,
        room,
        target,
        party);

    // 다른 호실의 링크 ID를 같이 넘겨도 이 호실의 통지만 본다.
    int cancelled = inSave(() -> cancellation.cancelPendingSms(room, List.of(target, otherLink)));

    assertThat(cancelled).isEqualTo(1);
    assertThat(outboxStatus(targetNotice)).isEqualTo("CANCELLED");
    for (UUID untouched : List.of(unlistedNotice, noLinkNotice, otherRoomNotice)) {
      assertThat(outboxStatus(untouched)).isEqualTo("PENDING");
      assertThat(noticeStatus(untouched)).isEqualTo("REQUESTED");
    }
    assertThat(noticeStatus(inApp)).isEqualTo("REQUESTED");
  }

  @ParameterizedTest
  @CsvSource({"SENDING, REQUESTED", "DONE, SENT", "FAILED, FAILED", "FAILED, REQUESTED"})
  void 보낼_차례가_아닌_줄은_통지와_함께_그대로다(String outboxStatus, String noticeStatus) {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    setOutbox(notice, outboxStatus);
    jdbc.update("UPDATE notice SET status = ? WHERE id = ?", noticeStatus, notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isZero();

    assertThat(outboxStatus(notice)).isEqualTo(outboxStatus);
    assertThat(noticeStatus(notice)).isEqualTo(noticeStatus);
  }

  @Test
  void 같은_요청을_다시_부르면_0이고_같은_트랜잭션_안에서도_0이다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);

    int[] sameTransaction =
        inSave(() ->
            new int[] {
              cancellation.cancelPendingSms(room, List.of(link)),
              cancellation.cancelPendingSms(room, List.of(link))
            });
    int again = inSave(() -> cancellation.cancelPendingSms(room, List.of(link)));

    assertThat(sameTransaction).containsExactly(1, 0);
    assertThat(again).isZero();
    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
  }

  @Test
  void 빈_목록이면_0이고_아무것도_바꾸지_않는다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of()))).isZero();
    // 빈 목록이면 호실도 보지 않는다(없는 호실이어도 0).
    assertThat(inSave(() -> cancellation.cancelPendingSms(UUID.randomUUID(), List.of()))).isZero();

    assertThat(outboxStatus(notice)).isEqualTo("PENDING");
    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
  }

  // ---- 이상한 데이터 ----

  @Test
  void 중복_방지_키가_다른_줄은_취소하지_않고_통지_ID로_남긴다(CapturedOutput output) {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    jdbc.update(
        "UPDATE outbox SET dedupe_key = ? WHERE dedupe_key = ?",
        "sms:" + UUID.randomUUID(),
        "sms:" + notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isZero();

    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM outbox WHERE payload ->> 'noticeId' = ?",
                String.class,
                notice.toString()))
        .isEqualTo("PENDING");
    assertThat(output.getAll()).contains("맞는 문자 줄이 없음").contains("notice=" + notice);
  }

  @Test
  void 키_모양이_같아도_문자_줄이_아닌_작업은_취소하지_않고_건수에도_넣지_않는다(CapturedOutput output) {
    UUID normalLink = link(room, party, false);
    UUID otherKindLink = link(room, party, false);
    UUID normal = enqueue(room, party, normalLink);
    UUID otherKind = enqueue(room, party, otherKindLink);
    // 정상 경로로는 생길 수 없다: 'sms:<통지 ID>' 키를 가진 다른 종류의 작업
    jdbc.update("UPDATE outbox SET kind = 'AUDIT_WRITE' WHERE dedupe_key = ?", "sms:" + otherKind);

    int cancelled =
        inSave(() -> cancellation.cancelPendingSms(room, List.of(normalLink, otherKindLink)));

    assertThat(cancelled).isEqualTo(1);
    assertThat(outboxStatus(normal)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(normal)).isEqualTo("CANCELLED");
    assertThat(outboxStatus(otherKind)).isEqualTo("PENDING");
    assertThat(noticeStatus(otherKind)).isEqualTo("REQUESTED");
    assertThat(output.getAll()).contains("맞는 문자 줄이 없음").contains("notice=" + otherKind);
  }

  @Test
  void payload가_다른_통지를_가리키는_줄은_취소하지_않고_남긴다(CapturedOutput output) {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    jdbc.update(
        "UPDATE outbox SET payload = CAST(? AS jsonb) WHERE dedupe_key = ?",
        "{\"noticeId\":\"" + UUID.randomUUID() + "\"}",
        "sms:" + notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isZero();

    assertThat(outboxStatus(notice)).isEqualTo("PENDING");
    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
    assertThat(output.getAll()).contains("다른 통지를 가리켜").contains("notice=" + notice);
  }

  @Test
  void 문자_줄이_없는_통지는_취소하지_않고_통지_ID로_남긴다(CapturedOutput output) {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    jdbc.update("DELETE FROM outbox WHERE dedupe_key = ?", "sms:" + notice);

    assertThat(inSave(() -> cancellation.cancelPendingSms(room, List.of(link)))).isZero();

    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
    assertThat(output.getAll()).contains("맞는 문자 줄이 없음").contains("notice=" + notice);
    assertThat(output.getAll()).doesNotContain("01012345678");
  }

  @Test
  void 보내기_전_줄인데_통지가_발송_요청됨이_아니면_행동_전체를_되돌린다() {
    UUID first = link(room, party, false);
    UUID second = link(room, party, false);
    UUID a = enqueue(room, party, first);
    UUID b = enqueue(room, party, second);
    // 잠그는 순서(ID 순)에서 뒤에 오는 통지를 이상하게 만들어, 앞 통지를 바꾼 뒤에 예외가 나게 한다.
    List<UUID> ordered =
        jdbc.queryForList("SELECT id FROM notice WHERE id IN (?, ?) ORDER BY id", UUID.class, a, b);
    UUID normal = ordered.get(0);
    UUID broken = ordered.get(1);
    jdbc.update("UPDATE notice SET status = 'FAILED' WHERE id = ?", broken);

    assertThatThrownBy(() ->
            inSave(() -> {
              // 번호 고치기에서 취소 앞에 오는 링크 무효를 흉내 낸다. 함께 되돌아가야 한다.
              jdbc.update(
                  "UPDATE link SET invalidated_at = CURRENT_TIMESTAMP,"
                      + " invalidated_reason = 'REINVITED' WHERE id = ?",
                  first);
              return cancellation.cancelPendingSms(room, List.of(first, second));
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notice=" + broken);

    assertThat(outboxStatus(normal)).isEqualTo("PENDING");
    assertThat(noticeStatus(normal)).isEqualTo("REQUESTED");
    assertThat(outboxStatus(broken)).isEqualTo("PENDING");
    assertThat(noticeStatus(broken)).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at IS NULL FROM link WHERE id = ?", Boolean.class, first))
        .isTrue();
  }

  // ---- 부르는 조건 ----

  @Test
  void 트랜잭션_밖에서_부르면_거절한다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);

    assertThatThrownBy(() -> cancellation.cancelPendingSms(room, List.of(link)))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(outboxStatus(notice)).isEqualTo("PENDING");
  }

  @Test
  void save_밖의_트랜잭션에서_부르면_거절하고_바꾸지_않는다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);

    assertThatThrownBy(
            () -> transaction.execute(s -> cancellation.cancelPendingSms(room, List.of(link))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("StateStore.save");

    assertThat(outboxStatus(notice)).isEqualTo("PENDING");
    assertThat(noticeStatus(notice)).isEqualTo("REQUESTED");
  }

  // ---- 작업자와 함께 ----

  @Test
  void 취소한_줄은_작업자가_가져가지_않는다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    recipients.seedParty(party, "01012345678");
    inSave(() -> cancellation.cancelPendingSms(room, List.of(link)));

    worker.runOnce();

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(gateway.sentBatchIds()).doesNotContain(notice);
  }

  @Test
  void 작업자는_가져간_줄의_초대_링크가_이미_무효면_보내지_않고_통지와_함께_취소한다() {
    UUID invalid = link(room, party, true);
    UUID notice = enqueue(room, party, invalid);
    recipients.seedParty(party, "01012345678");

    worker.runOnce();

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(attempts(notice)).isEqualTo(1);
    assertThat(gateway.sentBatchIds()).doesNotContain(notice);
  }

  @Test
  void 업체_RETRY_뒤_초대_링크가_무효가_되면_다시_시도할_때_보내지_않고_취소한다() {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    recipients.seedParty(party, "01012345678");
    gateway.respondWith(SmsSendResult.Outcome.RETRY);
    worker.runOnce();
    assertThat(outboxStatus(notice)).isEqualTo("PENDING");

    // 그사이 번호를 고쳐 옛 링크가 무효가 됐다(취소는 SENDING이던 줄을 놓쳤다). 다시 시도 시각도 지났다
    jdbc.update(
        "UPDATE link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE id = ?",
        link);
    jdbc.update(
        "UPDATE outbox SET next_attempt_at = CURRENT_TIMESTAMP - interval '1 minute'"
            + " WHERE dedupe_key = ?",
        "sms:" + notice);
    gateway.respondWith(SmsSendResult.Outcome.SENT);

    worker.runOnce();

    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(attempts(notice)).isEqualTo(2);
    assertThat(gateway.sentBatchIds()).doesNotContain(notice);
  }

  @Test
  void 취소가_줄을_잡고_있으면_작업자는_기다리지_않고_건너뛴다() throws Exception {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    recipients.seedParty(party, "01012345678");

    int cancelled =
        inSave(() -> {
          int n = cancellation.cancelPendingSms(room, List.of(link));
          // 아직 커밋 전: 통지·문자 줄 잠금을 잡고 있다. 작업자는 SKIP LOCKED라 막히지 않고 이 줄을 건너뛴다.
          join(CompletableFuture.runAsync(worker::runOnce));
          assertThat(gateway.sentBatchIds()).doesNotContain(notice);
          return n;
        });
    worker.runOnce();

    assertThat(cancelled).isEqualTo(1);
    assertThat(outboxStatus(notice)).isEqualTo("CANCELLED");
    assertThat(noticeStatus(notice)).isEqualTo("CANCELLED");
    assertThat(gateway.sentBatchIds()).doesNotContain(notice);
  }

  @Test
  void 작업자가_먼저_가져가_보내는_중인_줄은_취소하지_못한다() throws Exception {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    recipients.seedParty(party, "01012345678");
    // 같은 DB의 다른 보낼 줄보다 먼저 가져가게 한다.
    jdbc.update(
        "UPDATE outbox SET next_attempt_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE dedupe_key = ?",
        "sms:" + notice);
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SmsGateway blocking =
        new SmsGateway() {
          @Override
          public SmsSendResult send(SmsMessage message) {
            if (message.batchLeadId().equals(notice)) {
              sending.countDown();
              await(release);
            }
            return SmsSendResult.sent("blocked-" + message.batchLeadId());
          }

          @Override
          public Map<UUID, DeliveryOutcome> queryResults(List<DeliveryQuery> queries) {
            return Map.of();
          }
        };
    OutboxWorker slowWorker =
        new OutboxWorker(
            outboxRepository,
            noticeRepository,
            inviteLinks,
            recipients,
            blocking,
            clock,
            transactionManager,
            1,
            List.of(Duration.ofMinutes(1)),
            Duration.ofDays(3),
            deliveryPollSchedule);
    CompletableFuture<Integer> running = CompletableFuture.supplyAsync(slowWorker::runOnce);
    assertThat(sending.await(30, TimeUnit.SECONDS)).isTrue();

    // 작업자는 SENDING을 커밋하고 업체를 부르는 중이다. U2는 PENDING만 취소한다(#100 남은 위험).
    int cancelled = inSave(() -> cancellation.cancelPendingSms(room, List.of(link)));
    String statusWhileSending = outboxStatus(notice);
    release.countDown();
    running.get(30, TimeUnit.SECONDS);

    assertThat(cancelled).isZero();
    assertThat(statusWhileSending).isEqualTo("SENDING");
    assertThat(outboxStatus(notice)).isEqualTo("DONE");
    assertThat(noticeStatus(notice)).isEqualTo("SENT");
  }

  @ParameterizedTest
  @CsvSource({"true, 0, SENDING", "false, 1, CANCELLED"})
  void 작업자가_가져가는_중이면_기다렸다가_그_결과를_따른다(boolean claimCommits, int expected, String finalStatus)
      throws Exception {
    UUID link = link(room, party, false);
    UUID notice = enqueue(room, party, link);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // 작업자 가져가기(claim)의 DB 단계: 줄을 잠그고 SENDING으로 바꾼 뒤 아직 커밋하지 않음.
    CompletableFuture<Void> claim =
        CompletableFuture.runAsync(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.queryForList(
                  "SELECT id FROM outbox WHERE dedupe_key = ? FOR UPDATE SKIP LOCKED",
                  UUID.class,
                  "sms:" + notice);
              jdbc.update(
                  "UPDATE outbox SET status = 'SENDING', attempts = attempts + 1"
                      + " WHERE dedupe_key = ?",
                  "sms:" + notice);
              locked.countDown();
              await(release);
              if (!claimCommits) {
                status.setRollbackOnly();
              }
            }));
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Integer> cancel =
        CompletableFuture.supplyAsync(
            () -> inSave(() -> cancellation.cancelPendingSms(room, List.of(link))));
    awaitLockWaitOnOutbox();
    assertThat(cancel).isNotDone();
    release.countDown();
    claim.get(30, TimeUnit.SECONDS);

    assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
    assertThat(outboxStatus(notice)).isEqualTo(finalStatus);
    assertThat(noticeStatus(notice)).isEqualTo(claimCommits ? "REQUESTED" : "CANCELLED");
  }

  // ---- 도우미 ----

  /** pipeline {@code save} 안처럼 트랜잭션과 행동 시각 창구를 함께 연다. 창구 여닫기는 action 패키지 안이라 리플렉션으로 부른다. */
  private <T> T inSave(Supplier<T> work) {
    return transaction.execute(status -> {
      ReflectionTestUtils.invokeMethod(requestContext, "begin", UUID.randomUUID(), AT);
      try {
        return work.get();
      } finally {
        ReflectionTestUtils.invokeMethod(requestContext, "end");
      }
    });
  }

  /** 취소 트랜잭션이 문자 줄 잠금을 기다리는 것을 DB에서 볼 때까지 기다린다(시간으로 순서를 맞추지 않음). */
  private void awaitLockWaitOnOutbox() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              """
              SELECT count(*) FROM pg_stat_activity
              WHERE datname = current_database() AND wait_event_type = 'Lock'
                AND query ILIKE '%outbox%'
              """,
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
    throw new AssertionError("취소가 문자 줄 잠금을 기다리지 않았어요");
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999997', '취소 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    rooms.add(id);
    return id;
  }

  private UUID party(UUID roomId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'TENANT', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId);
    return id;
  }

  private UUID link(UUID roomId, UUID partyId, boolean invalidated) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,
          invalidated_at, invalidated_reason, created_at, version)
        VALUES (?, ?, ?, ?, 'INVITE', ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        partyId,
        member,
        id.toString(),
        invalidated ? java.sql.Timestamp.from(AT) : null,
        invalidated ? "REINVITED" : null);
    return id;
  }

  private UUID enqueue(UUID roomId, UUID partyId, UUID linkId) {
    NoticeKind kind = linkId == null ? NoticeKind.REQUEST : NoticeKind.INVITE;
    return transaction.execute(
        s -> noticeOutbox.enqueueSms(new SmsNoticeCommand(roomId, partyId, kind, null, linkId)));
  }

  private void setOutbox(UUID noticeId, String status) {
    jdbc.update("UPDATE outbox SET status = ? WHERE dedupe_key = ?", status, "sms:" + noticeId);
  }

  private String outboxStatus(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox WHERE dedupe_key = ?", String.class, "sms:" + noticeId);
  }

  private String noticeStatus(UUID noticeId) {
    return jdbc.queryForObject("SELECT status FROM notice WHERE id = ?", String.class, noticeId);
  }

  private int attempts(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM outbox WHERE dedupe_key = ?", Integer.class, "sms:" + noticeId);
  }

  private Instant nextAttemptAt(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT next_attempt_at FROM outbox WHERE dedupe_key = ?",
        Instant.class,
        "sms:" + noticeId);
  }

  private static void join(CompletableFuture<?> future) {
    try {
      future.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("작업자가 30초 안에 끝나지 않았어요(잠금에 막혔을 수 있음)", e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("30초 안에 풀리지 않았어요");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
