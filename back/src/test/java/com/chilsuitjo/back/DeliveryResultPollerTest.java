package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.service.DeliveryPollClaimer;
import com.chilsuitjo.back.notification.service.DeliveryPollScheduler;
import com.chilsuitjo.back.notification.service.DeliveryResultPoller;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 전달 결과 작업자(#120 3-B1): 가져가기(커밋) → 트랜잭션 밖 조회 → 통지마다 다시 잠가 적기. 커밋을 여러 번 하므로 테스트 트랜잭션으로 감싸지 않고, 시각은
 * 고정 시계로 정한다. 다른 테스트의 통지와 섞이지 않게 2020년과 전용 지역 코드를 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
class DeliveryResultPollerTest {

  private static final String REGION = "9999999996";
  private static final Instant SENT = Instant.parse("2020-01-01T00:00:00Z");
  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final Instant DEADLINE = SENT.plus(THREE_DAYS);
  private static final Instant FIRST_POLL = SENT.plus(Duration.ofMinutes(1));

  @Autowired private NoticeRepository notices;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;

  @Value("${app.delivery.poll}")
  private List<Duration> configuredPoll;

  @Value("${app.delivery.unknown-after}")
  private Duration configuredGiveUpAfter;

  private final FakeSmsGateway fake = new FakeSmsGateway();
  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES (?, '테스트', '결과동') ON CONFLICT DO NOTHING",
        REGION);
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room,
        REGION);
    party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room);
  }

  @AfterEach
  void cleanUp() {
    jdbc.update(
        "DELETE FROM notice WHERE room_record_id IN (SELECT id FROM room_record WHERE region_code = ?)",
        REGION);
  }

  @Test
  void 전달됨이면_전달됨과_적는_시각이_남고_더_묻지_않는다() {
    UUID id = notice(FIRST_POLL);
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
    Instant savedAt = FIRST_POLL.plusSeconds(3);

    poller(FIRST_POLL, savedAt, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(savedAt);
    assertThat(time(id, "next_poll_at")).isNull();
    assertThat(time(id, "sent_at")).isEqualTo(SENT);
  }

  @Test
  void 업체의_최종_실패면_실패로_끝나고_더_묻지_않는다() {
    UUID id = notice(FIRST_POLL);
    fake.respondDelivery(id, DeliveryOutcome.FAILED);

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "failed_at")).isEqualTo(FIRST_POLL);
    assertThat(time(id, "next_poll_at")).isNull();
  }

  @Test
  void 결과가_없고_기한_전이면_보낸_상태로_두고_정상_일정대로_다시_묻는다() {
    UUID id = notice(FIRST_POLL);

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    assertThat(time(id, "next_poll_at")).isEqualTo(SENT.plus(Duration.ofMinutes(5)));
  }

  @Test
  void 결과가_없는_채_기한이_지나면_전달_확인_기한_만료로_실패다() {
    UUID id = notice(DEADLINE);

    poller(DEADLINE, DEADLINE, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "failed_at")).isEqualTo(DEADLINE);
    assertThat(time(id, "next_poll_at")).isNull();
  }

  @Test
  void 기한_전_조회_실패는_보낸_상태로_두고_다시_보내지_않으며_옮긴_시각에_다시_묻는다() {
    UUID id = notice(FIRST_POLL);
    fake.failQueries(true);

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    Instant retryAt = SENT.plus(Duration.ofMinutes(5));
    assertThat(time(id, "next_poll_at")).isEqualTo(retryAt);
    assertThat(fake.sentBatchIds()).isEmpty();

    fake.failQueries(false);
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
    poller(retryAt, retryAt, fake).runOnce(50);
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
  }

  @Test
  void 기한이_지난_마지막_조회가_실패해도_전달_확인_기한_만료로_실패다() {
    UUID id = notice(DEADLINE);
    fake.failQueries(true);

    poller(DEADLINE, DEADLINE, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "next_poll_at")).isNull();
    assertThat(fake.sentBatchIds()).isEmpty();
  }

  @Test
  void 기한_전에_가져갔지만_적을_때_기한이_지났으면_실패다() {
    Instant claimAt = DEADLINE.minusSeconds(1);
    UUID id = notice(claimAt);

    poller(claimAt, DEADLINE.plusSeconds(1), fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
  }

  @Test
  void 기한이_지난_통지라도_마지막_조회에서_전달됨이면_전달됨이다() {
    UUID id = notice(DEADLINE);
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);

    poller(DEADLINE, DEADLINE, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
  }

  @Test
  void 묻는_사이_다른_쪽이_끝낸_통지는_덮어쓰지_않는다() {
    UUID delivered = notice(FIRST_POLL);
    UUID failed = notice(FIRST_POLL);
    Instant otherAt = FIRST_POLL.minusSeconds(5);
    SmsGateway racing =
        gateway(queries -> {
          finish(delivered, "DELIVERED", "delivered_at", otherAt);
          finish(failed, "FAILED", "failed_at", otherAt);
          return Map.of(delivered, DeliveryOutcome.FAILED, failed, DeliveryOutcome.DELIVERED);
        });

    poller(FIRST_POLL, FIRST_POLL, racing).runOnce(50);

    assertThat(column(delivered, "status")).isEqualTo("DELIVERED");
    assertThat(time(delivered, "delivered_at")).isEqualTo(otherAt);
    assertThat(time(delivered, "failed_at")).isNull();
    assertThat(column(failed, "status")).isEqualTo("FAILED");
    assertThat(time(failed, "failed_at")).isEqualTo(otherAt);
    assertThat(time(failed, "delivered_at")).isNull();
  }

  @Test
  void 가져간_뒤_멈추면_옮긴_시각에_다시_가져가_결과를_적는다() {
    UUID id = notice(FIRST_POLL);
    // 가져가기만 커밋하고 조회 전에 멈춘 것과 같다
    claimer(FIRST_POLL).claim(50);
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);

    Instant retryAt = SENT.plus(Duration.ofMinutes(5));
    assertThat(poller(retryAt.minusSeconds(1), retryAt, fake).runOnce(50)).isZero();
    poller(retryAt, retryAt, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
  }

  @Test
  void 업체가_모르는_ID를_돌려주거나_빠뜨려도_다른_통지에_적지_않는다() {
    UUID claimed = notice(FIRST_POLL);
    UUID notDue = notice(FIRST_POLL.plusSeconds(60));
    SmsGateway wrong =
        gateway(queries ->
            Map.of(
                UUID.randomUUID(), DeliveryOutcome.DELIVERED, notDue, DeliveryOutcome.DELIVERED));

    poller(FIRST_POLL, FIRST_POLL, wrong).runOnce(50);

    assertThat(column(claimed, "status")).isEqualTo("SENT");
    assertThat(column(notDue, "status")).isEqualTo("SENT");
    assertThat(time(notDue, "delivered_at")).isNull();
  }

  @Test
  void 잘못된_통지가_섞여도_정상_통지는_결과가_적히고_잘못된_통지는_상태가_바뀌지_않는다() {
    UUID broken = notice(FIRST_POLL.minusSeconds(1));
    jdbc.update("UPDATE notice SET batch_lead_id = NULL WHERE id = ?", broken);
    UUID good = notice(FIRST_POLL);
    fake.respondDelivery(good, DeliveryOutcome.DELIVERED);

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    assertThat(column(good, "status")).isEqualTo("DELIVERED");
    assertThat(column(broken, "status")).isEqualTo("SENT");
    assertThat(time(broken, "delivered_at")).isNull();
    assertThat(time(broken, "failed_at")).isNull();
  }

  @Test
  void 기한_만료로_통지가_실패해도_응답_요청은_기다림_그대로다() {
    UUID request = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, 'END_REQUEST', 'TENANT', 'WAITING', CURRENT_TIMESTAMP, 0)
        """,
        request,
        room);
    UUID id = notice(DEADLINE);
    jdbc.update("UPDATE notice SET response_request_id = ? WHERE id = ?", request, id);

    poller(DEADLINE, DEADLINE, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    // 통지 실패는 응답 완료·미확인·혼자 진행이 아니다(03 1-1 "받지도 못한 사람을 미확인으로 몰지 않는다")
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, solo_reason, first_notified_at, renotify_due_at, unconfirmed_due_at"
                + " FROM response_request WHERE id = ?",
            request);
    assertThat(row)
        .containsEntry("status", "WAITING")
        .containsEntry("solo_reason", null)
        .containsEntry("first_notified_at", null)
        .containsEntry("renotify_due_at", null)
        .containsEntry("unconfirmed_due_at", null);
  }

  @Test
  void 오래_꺼졌다_켜지면_기한이_지난_통지와_지나지_않은_통지를_한_번씩_처리한다() {
    Instant restartedAt = SENT.plus(Duration.ofDays(4));
    UUID expired = notice(FIRST_POLL);
    UUID delivered = notice(FIRST_POLL);
    fake.respondDelivery(delivered, DeliveryOutcome.DELIVERED);

    assertThat(poller(restartedAt, restartedAt, fake).runOnce(50)).isGreaterThanOrEqualTo(2);

    assertThat(column(expired, "status")).isEqualTo("FAILED");
    assertThat(column(delivered, "status")).isEqualTo("DELIVERED");
    assertThat(poller(restartedAt, restartedAt, fake).runOnce(50)).isZero();
    assertThat(fake.sentBatchIds()).isEmpty();
  }

  @Test
  void 설정의_조회_일정과_기한은_코드와_같다() {
    assertThat(configuredPoll)
        .containsExactly(
            DeliveryPollSchedule.FIRST_POLL,
            DeliveryPollSchedule.SECOND_POLL,
            DeliveryPollSchedule.INTERVAL);
    assertThat(configuredGiveUpAfter).isEqualTo(THREE_DAYS);
  }

  @Test
  void 테스트에서는_예약_실행_부품이_없다() {
    assertThat(context.getBeanNamesForType(DeliveryPollScheduler.class)).isEmpty();
  }

  // ---- 두 작업자 경합(#120): A가 묻는 사이 B가 다시 가져간다. A는 기한 1초 전에 가져가 기한을 다음 시각으로 잡고, B는 기한에 다시 가져간다

  @Test
  void 다시_가져간_뒤_오래된_결과_없음이_먼저_와도_실패로_적지_않고_최신_전달됨이_남는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));

    race(id, () -> DeliveryOutcome.NONE, () -> DeliveryOutcome.DELIVERED, false);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(2));
  }

  @Test
  void 다시_가져간_뒤_오래된_조회_예외가_먼저_와도_실패로_적지_않고_최신_전달됨이_남는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));

    race(
        id,
        () -> {
          throw new IllegalStateException("오래된 조회 시간 초과");
        },
        () -> DeliveryOutcome.DELIVERED,
        false);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
  }

  @Test
  void 최신_작업자의_전달됨이_먼저_적히면_오래된_결과는_아무것도_바꾸지_않는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));

    race(id, () -> DeliveryOutcome.NONE, () -> DeliveryOutcome.DELIVERED, true);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(2));
    assertThat(time(id, "failed_at")).isNull();
  }

  @Test
  void 오래된_작업자의_전달됨은_아직_보낸_상태면_남기고_최신_결과_없음은_덮어쓰지_않는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));

    race(id, () -> DeliveryOutcome.DELIVERED, () -> DeliveryOutcome.NONE, false);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(1));
    assertThat(time(id, "failed_at")).isNull();
  }

  @Test
  void 오래된_작업자의_최종_실패는_적지_않는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant claimA = DEADLINE.minusSeconds(1);
    SmsGateway stale =
        gateway(queries -> {
          // 묻는 사이 다른 작업자가 다시 가져갔다(결과는 아직 적지 않음)
          claimer(DEADLINE).claim(50);
          return Map.of(id, DeliveryOutcome.FAILED);
        });

    poller(claimA, DEADLINE.plusSeconds(1), stale).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    assertThat(time(id, "next_poll_at")).isEqualTo(DEADLINE.plus(Duration.ofMinutes(15)));
  }

  /**
   * 알려진 한계(후속 결정 필요): 최신 작업자 B가 기한 뒤 결과 없음으로 먼저 실패를 적으면, 그 뒤에 온 오래된 작업자 A의 전달됨은 반영되지 않는다. 상태는
   * 앞으로만이라 FAILED를 DELIVERED로 되돌리지 않기 때문이다(04 4-2). 이 테스트는 지금 동작을 고정해 정책이 바뀌면 드러나게 한다.
   */
  @Test
  void 알려진_한계_최신_작업자의_만료_실패가_먼저_적히면_오래된_전달됨은_반영되지_않는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));

    race(id, () -> DeliveryOutcome.DELIVERED, () -> DeliveryOutcome.NONE, true);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "delivered_at")).isNull();
  }

  @Test
  void 나노초_시계로_가져가도_저장된_조회_시각과_맞아_결과가_적힌다() {
    // 보낸 뒤 10분에 가져가면 다음 시각이 "지금 + 15분"이라 나노초가 그대로 실린다(마이크로초로 맞추지 않으면 세대가 달라 보여 실패가 버려짐)
    Instant due = SENT.plus(Duration.ofMinutes(10));
    UUID id = notice(due);
    fake.respondDelivery(id, DeliveryOutcome.FAILED);
    Instant nanos = due.plusNanos(123_456_789);

    poller(nanos, nanos.plusNanos(987), fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
  }

  /**
   * A가 묻는 동안 B가 다시 가져가 묻게 하고, 저장 순서를 정한다. A는 기한 1초 전 가져감(기한 + 1초에 적음), B는 기한에 가져감(기한 + 2초에 적음).
   *
   * @param bFinishesFirst 참이면 B가 묻고 적기까지 마친 뒤 A가 적는다. 거짓이면 B가 묻는 중에 A가 먼저 적고, 그 뒤 B가 적는다
   */
  private void race(
      UUID id,
      Supplier<DeliveryOutcome> aAnswer,
      Supplier<DeliveryOutcome> bAnswer,
      boolean bFinishesFirst)
      throws Exception {
    CountDownLatch bQuerying = new CountDownLatch(1);
    CountDownLatch aSaved = new CountDownLatch(1);
    DeliveryResultPoller b =
        poller(
            DEADLINE,
            DEADLINE.plusSeconds(2),
            gateway(queries -> {
              bQuerying.countDown();
              if (!bFinishesFirst) {
                await(aSaved);
              }
              return Map.of(id, bAnswer.get());
            }));
    AtomicReference<CompletableFuture<Integer>> bRun = new AtomicReference<>();
    DeliveryResultPoller a =
        poller(
            DEADLINE.minusSeconds(1),
            DEADLINE.plusSeconds(1),
            gateway(queries -> {
              bRun.set(CompletableFuture.supplyAsync(() -> b.runOnce(50)));
              if (bFinishesFirst) {
                bRun.get().join();
              } else {
                await(bQuerying);
              }
              return Map.of(id, aAnswer.get());
            }));

    a.runOnce(50);
    aSaved.countDown();
    assertThat(bRun.get().get(10, TimeUnit.SECONDS)).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("기다림 시간 초과");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private DeliveryPollClaimer claimer(Instant at) {
    return new DeliveryPollClaimer(
        notices, Clock.fixed(at, ZoneOffset.UTC), transactionManager, THREE_DAYS);
  }

  /** 가져갈 때와 적을 때의 시각을 따로 정한다(업체에 묻는 사이 시간이 흐름). */
  private DeliveryResultPoller poller(Instant claimAt, Instant saveAt, SmsGateway gateway) {
    return new DeliveryResultPoller(
        claimer(claimAt),
        notices,
        gateway,
        Clock.fixed(saveAt, ZoneOffset.UTC),
        transactionManager,
        THREE_DAYS,
        List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));
  }

  private static SmsGateway gateway(
      Function<List<DeliveryQuery>, Map<UUID, DeliveryOutcome>> answer) {
    return new SmsGateway() {
      @Override
      public SmsSendResult send(SmsMessage message) {
        throw new UnsupportedOperationException("전달 결과 작업자는 문자를 보내지 않음");
      }

      @Override
      public Map<UUID, DeliveryOutcome> queryResults(List<DeliveryQuery> queries) {
        return answer.apply(queries);
      }
    };
  }

  /** 다른 트랜잭션(다른 연결)이 통지를 끝낸 것처럼 바꾼다. */
  private void finish(UUID id, String status, String column, Instant at) {
    jdbc.update(
        "UPDATE notice SET status = ?, " + column + " = ?, next_poll_at = NULL WHERE id = ?",
        status,
        Timestamp.from(at),
        id);
  }

  /** 보낸 시각은 {@link #SENT}, 묶음 ID는 자기 자신(Notice.markSent와 같음). */
  private UUID notice(Instant nextPollAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, recipient_party_id, kind, channel, status,
          sent_at, provider_message_id, batch_lead_id, next_poll_at, created_at, version)
        VALUES (?, ?, ?, 'REQUEST', 'SMS', 'SENT', ?, ?, ?, ?, ?, 0)
        """,
        id,
        room,
        party,
        Timestamp.from(SENT),
        "fake-" + id,
        id,
        Timestamp.from(nextPollAt),
        Timestamp.from(SENT.minusSeconds(10)));
    return id;
  }

  private String column(UUID id, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM notice WHERE id = ?", String.class, id);
  }

  private Instant time(UUID id, String column) {
    Timestamp value =
        jdbc.queryForObject("SELECT " + column + " FROM notice WHERE id = ?", Timestamp.class, id);
    return value == null ? null : value.toInstant();
  }
}
