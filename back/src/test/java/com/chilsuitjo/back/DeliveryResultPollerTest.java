package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.notification.dto.ClaimedDeliveryPoll;
import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.service.DeliveryPollClaimer;
import com.chilsuitjo.back.notification.service.DeliveryPollScheduler;
import com.chilsuitjo.back.notification.service.DeliveryResultPoller;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
@ActiveProfiles("test")
class DeliveryResultPollerTest {

  private static final String REGION = "9999999996";
  private static final Instant SENT = Instant.parse("2020-01-01T00:00:00Z");
  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final Duration RENOTIFY = Duration.ofHours(48);
  private static final Instant DEADLINE = SENT.plus(THREE_DAYS);
  private static final Instant FIRST_POLL = SENT.plus(Duration.ofMinutes(1));
  private static final DeliveryPollSchedule SCHEDULE =
      new DeliveryPollSchedule(
          List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

  @Autowired private NoticeRepository notices;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private ResponseRequestRepository requests;
  @Autowired private RoomGateReader rooms;
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
    jdbc.update(
        "DELETE FROM response_request WHERE room_record_id IN"
            + " (SELECT id FROM room_record WHERE region_code = ?)",
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
  void 전달_결과를_확인한_시각을_저장_시각과_구분해_남긴다() {
    UUID id = notice(FIRST_POLL);
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
    Instant observedAt = FIRST_POLL.plusSeconds(2);
    Instant savingAt = FIRST_POLL.plusSeconds(3);
    AtomicInteger reads = new AtomicInteger();
    Clock advancingClock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return switch (reads.getAndIncrement()) {
              case 0 -> FIRST_POLL;
              case 1 -> observedAt;
              default -> savingAt;
            };
          }
        };

    new DeliveryResultPoller(
            claimer(FIRST_POLL),
            notices,
            fake,
            advancingClock,
            transactionManager,
            Duration.ofMinutes(2),
            waitStart)
        .runOnce(50);

    assertThat(reads.get()).isEqualTo(3);
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(observedAt);
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
  void 결과_없음의_만료는_저장된_기한의_직전_같은_시각_직후로_판단한다() {
    // 보낸 뒤 3일이 아니라 보낼 때 저장한 기한(5분)을 따른다
    Instant due = SENT.plus(Duration.ofMinutes(5));
    UUID justBefore = notice(due.minusNanos(1_000), due);
    poller(due.minusNanos(1_000), due.minusNanos(1_000), fake).runOnce(50);
    assertThat(column(justBefore, "status")).isEqualTo("SENT");
    assertThat(time(justBefore, "delivery_check_due_at")).isEqualTo(due);

    UUID exactly = notice(due, due);
    poller(due, due, fake).runOnce(50);
    assertThat(column(exactly, "status")).isEqualTo("FAILED");
    assertThat(column(justBefore, "status")).isEqualTo("FAILED");

    UUID justAfter = notice(due.plusNanos(1_000), due);
    poller(due.plusNanos(1_000), due.plusNanos(1_000), fake).runOnce(50);
    assertThat(column(justAfter, "status")).isEqualTo("FAILED");
    assertThat(time(justAfter, "delivery_check_due_at")).isEqualTo(due);
  }

  @Test
  void 저장된_기한이_3일보다_길면_3일이_지나도_결과_없음으로_실패하지_않는다() {
    Instant sevenDays = SENT.plus(Duration.ofDays(7));
    UUID id = notice(DEADLINE, sevenDays);

    poller(DEADLINE, DEADLINE.plusSeconds(1), fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    assertThat(time(id, "failed_at")).isNull();
    assertThat(time(id, "delivery_check_due_at")).isEqualTo(sevenDays);
  }

  @Test
  void 가져간_뒤_적기_전에_저장된_기한이_지나면_결과_없음은_실패다() {
    Instant due = SENT.plus(Duration.ofMinutes(5));
    Instant claimAt = due.minusSeconds(1);
    UUID id = notice(claimAt, due);

    poller(claimAt, due, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "failed_at")).isEqualTo(due);
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
  void 기한_전_claim만으로_보호하지_않고_batch_논리_시작이_기한_후면_실패한다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant batchStartedAt = DEADLINE.plusSeconds(1);
    Clock workerClock =
        sequenceClock(batchStartedAt, batchStartedAt.plusSeconds(1), batchStartedAt.plusSeconds(2));
    SmsGateway noResult =
        gateway(queries -> {
          assertThat(time(id, "delivery_poll_started_at")).isEqualTo(batchStartedAt);
          assertThat(
                  notices
                      .findById(id)
                      .orElseThrow()
                      .hasProtectedDeliveryPoll(batchStartedAt, DEADLINE))
              .isFalse();
          return Map.of(id, DeliveryOutcome.NONE);
        });

    poller(DEADLINE.minusSeconds(1), workerClock, noResult).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "delivered_at")).isNull();
  }

  @Test
  void 정확히_3일_기한에_논리_시작한_조회는_기한_전_보호가_아니다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Clock workerClock = sequenceClock(DEADLINE, DEADLINE, DEADLINE);
    SmsGateway noResult =
        gateway(queries -> {
          var inFlight = notices.findById(id).orElseThrow();
          assertThat(time(id, "delivery_poll_started_at")).isEqualTo(DEADLINE);
          assertThat(inFlight.hasProtectedDeliveryPoll(DEADLINE, DEADLINE)).isFalse();
          return Map.of(id, DeliveryOutcome.NONE);
        });

    poller(DEADLINE.minusSeconds(1), workerClock, noResult).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
  }

  @Test
  void batch_앞뒤_통지는_하나의_논리_시작과_보호_종료를_공유한다() {
    UUID first = notice(FIRST_POLL);
    UUID last = notice(FIRST_POLL);
    Instant batchStartedAt = DEADLINE.minusSeconds(1);
    Instant observedAt = DEADLINE.plusSeconds(1);
    Instant savedAt = observedAt.plusSeconds(1);
    AtomicInteger clockReads = new AtomicInteger();
    Clock workerClock = sequenceClock(clockReads, batchStartedAt, observedAt, savedAt);
    SmsGateway delivered =
        gateway(queries -> {
          assertThat(clockReads).hasValue(1);
          assertThat(queries).hasSize(2);
          assertThat(time(first, "delivery_poll_started_at")).isEqualTo(batchStartedAt);
          assertThat(time(last, "delivery_poll_started_at")).isEqualTo(batchStartedAt);
          assertThat(time(first, "delivery_poll_protected_until"))
              .isEqualTo(batchStartedAt.plus(Duration.ofMinutes(2)));
          assertThat(time(last, "delivery_poll_protected_until"))
              .isEqualTo(batchStartedAt.plus(Duration.ofMinutes(2)));
          return Map.of(first, DeliveryOutcome.DELIVERED, last, DeliveryOutcome.DELIVERED);
        });

    poller(FIRST_POLL, workerClock, delivered).runOnce(50);

    assertThat(column(first, "status")).isEqualTo("DELIVERED");
    assertThat(column(last, "status")).isEqualTo("DELIVERED");
    assertThat(time(first, "delivered_at")).isEqualTo(observedAt);
    assertThat(time(last, "delivered_at")).isEqualTo(observedAt);
  }

  /**
   * 문자 취소({@code NoticeRepository.lockByLinks})는 통지를 ID 순서로 잠근다. 조회 시작도 같은 순서여야 둘이 같은 통지 여러 건을 반대
   * 순서로 잡고 서로 기다리지 않는다. ID가 큰 통지가 먼저 조회 예정이어도, 작은 통지를 기다리는 동안 큰 통지는 잡고 있지 않아야 한다.
   */
  @Test
  void 조회_시작은_조회_예정_순서가_아니라_ID_순서로_통지를_잠근다() throws Exception {
    UUID a = notice(FIRST_POLL);
    UUID b = notice(FIRST_POLL);
    List<UUID> inIdOrder =
        jdbc.queryForList("SELECT id FROM notice WHERE id IN (?, ?) ORDER BY id", UUID.class, a, b);
    UUID lower = inIdOrder.get(0);
    UUID higher = inIdOrder.get(1);
    // 큰 ID를 먼저 가져가게 한다(가져가기는 조회 예정 시각 순)
    jdbc.update(
        "UPDATE notice SET next_poll_at = ? WHERE id = ?",
        Timestamp.from(FIRST_POLL.minusSeconds(30)),
        higher);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // 가져가기는 잠긴 줄을 건너뛰므로(SKIP LOCKED) 가져가기가 커밋된 뒤, 조회 시작 전에 작은 ID를 잡는다
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(() -> {
          await(claimed);
          tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM notice WHERE id = ? FOR UPDATE", lower);
            holding.countDown();
            await(release);
          });
        });
    DeliveryPollClaimer thenHold =
        new DeliveryPollClaimer(
            notices, Clock.fixed(FIRST_POLL, ZoneOffset.UTC), transactionManager, SCHEDULE) {
          @Override
          public List<ClaimedDeliveryPoll> claim(int limit) {
            List<ClaimedDeliveryPoll> polls = super.claim(limit);
            claimed.countDown();
            await(holding);
            return polls;
          }
        };
    DeliveryResultPoller poller =
        new DeliveryResultPoller(
            thenHold,
            notices,
            fake,
            Clock.fixed(FIRST_POLL, ZoneOffset.UTC),
            transactionManager,
            Duration.ofMinutes(2),
            waitStart);
    CompletableFuture<Integer> polling = CompletableFuture.supplyAsync(() -> poller.runOnce(50));
    try {
      awaitLockWaiter();

      // 작업자가 작은 ID를 기다리는 동안 큰 ID는 다른 쪽이 바로 잠글 수 있다
      List<UUID> lockedNow =
          tx.execute(status ->
              jdbc.queryForList(
                  "SELECT id FROM notice WHERE id = ? FOR UPDATE NOWAIT", UUID.class, higher));
      assertThat(lockedNow).containsExactly(higher);
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      assertThat(polling.get(10, TimeUnit.SECONDS)).isEqualTo(2);
    }
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

  // ---- 기한 전 시작한 A가 보호 중이면 B는 기한에 재선점할 수 없다

  @Test
  void 서로_다른_작업자가_경합해도_기한_전_조회에서_확인한_전달됨이_남는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));
    CountDownLatch querying = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SmsGateway delayed =
        gateway(queries -> {
          querying.countDown();
          await(release);
          return Map.of(id, DeliveryOutcome.DELIVERED);
        });
    CompletableFuture<Integer> first =
        CompletableFuture.supplyAsync(
            () -> poller(DEADLINE.minusSeconds(1), DEADLINE.plusSeconds(1), delayed).runOnce(50));
    try {
      await(querying);
      assertThat(poller(DEADLINE, DEADLINE, fake).runOnce(50)).isZero();
    } finally {
      release.countDown();
    }
    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(1));
  }

  @Test
  void 가져가기만_하고_조회가_시작되지_않으면_기한에_회수해_실패를_기록한다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    assertThat(claimer(DEADLINE.minusSeconds(1)).claim(50)).hasSize(1);

    assertThat(poller(DEADLINE, DEADLINE, fake).runOnce(50)).isEqualTo(1);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "delivered_at")).isNull();
  }

  @Test
  void 보호_상한이_지나면_다른_작업자가_회수하고_늦은_응답은_최종_상태를_바꾸지_않는다() throws Exception {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant recovery = DEADLINE.plus(Duration.ofMinutes(2));
    CountDownLatch querying = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SmsGateway delayed =
        gateway(queries -> {
          querying.countDown();
          await(release);
          return Map.of(id, DeliveryOutcome.DELIVERED);
        });
    CompletableFuture<Integer> first =
        CompletableFuture.supplyAsync(
            () -> poller(DEADLINE.minusSeconds(1), recovery.plusSeconds(2), delayed).runOnce(50));
    try {
      await(querying);
      assertThat(poller(recovery, recovery.plusSeconds(1), fake).runOnce(50)).isEqualTo(1);
    } finally {
      release.countDown();
    }
    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "delivered_at")).isNull();
  }

  @Test
  void 진행_중인_조회가_있으면_기한에_다른_작업자는_가져가지_못한다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    SmsGateway delivered =
        gateway(queries -> {
          assertThat(claimer(DEADLINE).claim(50)).isEmpty();
          assertThat(time(id, "next_poll_at"))
              .isEqualTo(DEADLINE.minusSeconds(1).plus(Duration.ofMinutes(2)));
          return Map.of(id, DeliveryOutcome.DELIVERED);
        });
    poller(DEADLINE.minusSeconds(1), DEADLINE.plusSeconds(1), delivered).runOnce(50);
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(1));
  }

  @Test
  void 보호_중인_조회가_명시적_최종_실패를_받으면_그_결과를_기록한다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    SmsGateway failed =
        gateway(queries -> {
          assertThat(claimer(DEADLINE).claim(50)).isEmpty();
          return Map.of(id, DeliveryOutcome.FAILED);
        });

    poller(DEADLINE.minusSeconds(1), DEADLINE.plusSeconds(1), failed).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("FAILED");
    assertThat(time(id, "failed_at")).isEqualTo(DEADLINE.plusSeconds(1));
  }

  @Test
  void 조회_보호_설정은_양수이면서_15분_이하여야_한다() {
    assertThatThrownBy(() ->
            new DeliveryResultPoller(
                claimer(FIRST_POLL),
                notices,
                fake,
                Clock.systemUTC(),
                transactionManager,
                Duration.ZERO,
                waitStart))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new DeliveryResultPoller(
                claimer(FIRST_POLL),
                notices,
                fake,
                Clock.systemUTC(),
                transactionManager,
                Duration.ofMinutes(16),
                waitStart))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 보호_중_조회_예외도_기한_뒤에는_결과_없음으로_실패한다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    SmsGateway failed =
        gateway(queries -> {
          assertThat(claimer(DEADLINE).claim(50)).isEmpty();
          throw new IllegalStateException("조회 시간 초과");
        });
    poller(DEADLINE.minusSeconds(1), DEADLINE.plusSeconds(1), failed).runOnce(50);
    assertThat(column(id, "status")).isEqualTo("FAILED");
  }

  @Test
  void 보호_상한_후_다른_작업자의_전달됨이_먼저_적히면_오래된_결과를_버린다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant recovery = DEADLINE.plus(Duration.ofMinutes(2));
    SmsGateway stale =
        gateway(queries -> {
          fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
          assertThat(poller(recovery, recovery.plusSeconds(1), fake).runOnce(50)).isEqualTo(1);
          return Map.of(id, DeliveryOutcome.NONE);
        });
    poller(DEADLINE.minusSeconds(1), recovery.plusSeconds(2), stale).runOnce(50);
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(recovery.plusSeconds(1));
    assertThat(time(id, "failed_at")).isNull();
  }

  @Test
  void 보호_상한_안에_전달됨이_저장되면_이후_기한_조회가_덮어쓰지_않는다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    SmsGateway delivered =
        gateway(queries -> {
          assertThat(claimer(DEADLINE).claim(50)).isEmpty();
          return Map.of(id, DeliveryOutcome.DELIVERED);
        });
    poller(DEADLINE.minusSeconds(1), DEADLINE.plusSeconds(1), delivered).runOnce(50);
    assertThat(claimer(DEADLINE.plusSeconds(2)).claim(50)).isEmpty();
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(time(id, "delivered_at")).isEqualTo(DEADLINE.plusSeconds(1));
    assertThat(time(id, "failed_at")).isNull();
  }

  @Test
  void 보호_상한이_지난_오래된_작업자의_최종_실패는_적지_않는다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant claimA = DEADLINE.minusSeconds(1);
    SmsGateway stale =
        gateway(queries -> {
          // 묻는 사이 다른 작업자가 다시 가져갔다(결과는 아직 적지 않음)
          claimer(DEADLINE.plus(Duration.ofMinutes(2))).claim(50);
          return Map.of(id, DeliveryOutcome.FAILED);
        });

    poller(claimA, DEADLINE.plus(Duration.ofMinutes(2)).plusSeconds(1), stale).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    assertThat(time(id, "next_poll_at")).isEqualTo(DEADLINE.plus(Duration.ofMinutes(17)));
  }

  /** 유한한 보호 상한 이후에는 이전 조회의 전달 증거를 반드시 보존한다고 보장하지 않는다. 새 작업자가 기한 만료 실패를 확정했다면 최종 상태를 뒤집지 않는다. */
  @Test
  void 보호_상한_후_새_작업자가_만료_실패를_확정하면_오래된_전달됨은_반영되지_않는다() {
    UUID id = notice(DEADLINE.minusSeconds(1));
    Instant recovery = DEADLINE.plus(Duration.ofMinutes(2));
    SmsGateway delayed =
        gateway(queries -> {
          assertThat(poller(recovery, recovery.plusSeconds(1), fake).runOnce(50)).isEqualTo(1);
          return Map.of(id, DeliveryOutcome.DELIVERED);
        });
    poller(DEADLINE.minusSeconds(1), recovery.plusSeconds(2), delayed).runOnce(50);
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

  /** 다른 연결이 행 잠금을 기다리기 시작할 때까지 기다린다. */
  /** 호실 행을 잠그려고 기다리는 연결이 생길 때까지. 다른 잠금 대기와 섞이지 않게 쿼리로 좁힌다. */
  private void awaitRoomLockWaiter() throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                  + " AND query ILIKE '%room_record%'"
                  + " AND pid <> pg_backend_pid()",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("호실 잠금을 기다리는 연결이 없음");
  }

  private void awaitLockWaiter() throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("잠금을 기다리는 연결이 없음");
  }

  private DeliveryPollClaimer claimer(Instant at) {
    return new DeliveryPollClaimer(
        notices, Clock.fixed(at, ZoneOffset.UTC), transactionManager, SCHEDULE);
  }

  /** 가져갈 때와 적을 때의 시각을 따로 정한다(업체에 묻는 사이 시간이 흐름). */
  // ---- 요청에 걸린 통지가 닿으면 기다림 시작(03 1-1 "타이머 시작점", 05 4장 "전달 결과 조회") ----

  @Test
  void 요청에_걸린_첫_통지가_전달되면_같은_트랜잭션에서_기다림이_시작된다() {
    UUID request = linkedRequest("WAITING");
    UUID id = linkedNotice(request, "REQUEST");
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);

    poller(FIRST_POLL, FIRST_POLL.plusSeconds(3), fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    Instant delivered = time(id, "delivered_at");
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(delivered);
    // 06 0-1 wait.renotify(48h). 미확인 기한은 재통지가 닿아야 정한다
    assertThat(requestTime(request, "renotify_due_at")).isEqualTo(delivered.plus(RENOTIFY));
    assertThat(requestTime(request, "unconfirmed_due_at")).isNull();
  }

  @Test
  void 요청_통지가_아닌_통지가_닿는_것은_기다림_시작이_아니다() {
    UUID request = linkedRequest("WAITING");
    List<UUID> others =
        List.of(
            linkedNotice(request, "RENOTIFY"),
            linkedNotice(request, "INFO"),
            linkedNotice(request, "INVITE"));
    others.forEach(id -> fake.respondDelivery(id, DeliveryOutcome.DELIVERED));

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    others.forEach(id -> assertThat(column(id, "status")).isEqualTo("DELIVERED"));
    assertThat(requestTime(request, "first_notified_at")).isNull();
    assertThat(requestTime(request, "renotify_due_at")).isNull();
  }

  @Test
  void 끝난_요청에_늦게_온_전달은_통지에만_남는다() {
    UUID request = linkedRequest("ANSWERED");
    UUID id = linkedNotice(request, "REQUEST");
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);

    poller(FIRST_POLL, FIRST_POLL, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(requestColumn(request, "status")).isEqualTo("ANSWERED");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 기다림_시작이_실패하면_전달됨도_되돌아가_다음_조회에서_다시_적는다() {
    UUID request = linkedRequest("WAITING");
    UUID id = linkedNotice(request, "REQUEST");
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
    NoticeWaitStart failing =
        new NoticeWaitStart(notices, requests, rooms, RENOTIFY) {
          @Override
          public boolean reached(Notice notice, Instant at) {
            throw new IllegalStateException("기다림 시작 실패");
          }
        };

    new DeliveryResultPoller(
            claimer(FIRST_POLL),
            notices,
            fake,
            advancingClock(FIRST_POLL, FIRST_POLL),
            transactionManager,
            Duration.ofMinutes(2),
            failing)
        .runOnce(50);

    assertThat(column(id, "status")).isEqualTo("SENT");
    assertThat(time(id, "delivered_at")).isNull();
    assertThat(requestTime(request, "first_notified_at")).isNull();

    // 가져갈 때 옮겨 둔 다음 조회 시각(보호 시간 뒤, 전달 확인 기한 전)에 다시 가져가 이번에는 함께 적는다
    Instant nextPoll = time(id, "next_poll_at");
    assertThat(nextPoll).isAfter(FIRST_POLL).isBefore(DEADLINE);
    poller(nextPoll, nextPoll, fake).runOnce(50);

    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(time(id, "delivered_at"));
  }

  @Test
  void 요청에_걸린_통지는_호실을_먼저_잠가_행동과_같은_순서로_기다린다() throws Exception {
    UUID request = linkedRequest("WAITING");
    UUID id = linkedNotice(request, "REQUEST");
    fake.respondDelivery(id, DeliveryOutcome.DELIVERED);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(() ->
            tx.executeWithoutResult(status -> {
              // 행동(pipeline 1단계)처럼 호실 행을 잡고 있는다
              jdbc.queryForList("SELECT id FROM room_record WHERE id = ? FOR UPDATE", room);
              locked.countDown();
              await(release);
            }));
    await(locked);
    DeliveryResultPoller poller = poller(FIRST_POLL, FIRST_POLL, fake);
    CompletableFuture<Integer> polling = CompletableFuture.supplyAsync(() -> poller.runOnce(50));
    try {
      awaitRoomLockWaiter();

      // 작업자는 호실에서 기다리고 통지는 아직 잡지 않았다(호실 → 통지 → 요청)
      List<UUID> noticeFree =
          tx.execute(status ->
              jdbc.queryForList(
                  "SELECT id FROM notice WHERE id = ? FOR UPDATE NOWAIT", UUID.class, id));
      assertThat(noticeFree).containsExactly(id);
      assertThat(column(id, "status")).isEqualTo("SENT");
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      assertThat(polling.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(column(id, "status")).isEqualTo("DELIVERED");
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(time(id, "delivered_at"));
  }

  @Test
  void 다른_호실의_요청에는_통지를_걸_수_없다() {
    UUID otherRoom = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, '다른 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        otherRoom,
        REGION);
    UUID otherRequest = requestIn(otherRoom, "WAITING");

    assertThatThrownBy(() -> linkedNotice(otherRequest, "REQUEST"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private DeliveryResultPoller poller(Instant claimAt, Instant saveAt, SmsGateway gateway) {
    return poller(claimAt, advancingClock(claimAt, saveAt), gateway);
  }

  private DeliveryResultPoller poller(Instant claimAt, Clock workerClock, SmsGateway gateway) {
    return new DeliveryResultPoller(
        claimer(claimAt),
        notices,
        gateway,
        workerClock,
        transactionManager,
        Duration.ofMinutes(2),
        waitStart);
  }

  private static Clock sequenceClock(Instant... instants) {
    return sequenceClock(new AtomicInteger(), instants);
  }

  private static Clock sequenceClock(AtomicInteger reads, Instant... instants) {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        int index = reads.getAndIncrement();
        return instants[Math.min(index, instants.length - 1)];
      }
    };
  }

  private static Clock advancingClock(Instant startAt, Instant resultAt) {
    AtomicInteger reads = new AtomicInteger();
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return reads.getAndIncrement() == 0 ? startAt : resultAt;
      }
    };
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

  /** 보낸 시각은 {@link #SENT}, 묶음 ID는 자기 자신, 전달 확인 기한은 보낸 뒤 3일(Notice.markSent와 같음). */
  private UUID linkedRequest(String status) {
    return requestIn(room, status);
  }

  /** 입주일 고친 내용 확인처럼 대상 칸이 빈(호실 기록) 요청. */
  private UUID requestIn(UUID roomId, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, 'CORRECTION_CONFIRM', 'TENANT', ?, ?, 0)
        """,
        id,
        roomId,
        status,
        Timestamp.from(SENT.minusSeconds(20)));
    return id;
  }

  private UUID linkedNotice(UUID requestId, String kind) {
    UUID id = notice(FIRST_POLL);
    jdbc.update(
        "UPDATE notice SET response_request_id = ?, kind = ? WHERE id = ?", requestId, kind, id);
    return id;
  }

  private String requestColumn(UUID id, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM response_request WHERE id = ?", String.class, id);
  }

  private Instant requestTime(UUID id, String column) {
    Timestamp value =
        jdbc.queryForObject(
            "SELECT " + column + " FROM response_request WHERE id = ?", Timestamp.class, id);
    return value == null ? null : value.toInstant();
  }

  private UUID notice(Instant nextPollAt) {
    return notice(nextPollAt, DEADLINE);
  }

  /** 보낼 때 저장된 전달 확인 기한을 정해 만든다(설정을 바꾸기 전후에 보낸 통지). */
  private UUID notice(Instant nextPollAt, Instant deliveryCheckDueAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, recipient_party_id, kind, channel, status,
          sent_at, provider_message_id, batch_lead_id, next_poll_at, delivery_check_due_at,
          created_at, version)
        VALUES (?, ?, ?, 'REQUEST', 'SMS', 'SENT', ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        room,
        party,
        Timestamp.from(SENT),
        "fake-" + id,
        id,
        Timestamp.from(nextPollAt),
        Timestamp.from(deliveryCheckDueAt),
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
