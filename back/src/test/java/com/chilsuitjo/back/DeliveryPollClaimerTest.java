package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.chilsuitjo.back.notification.dto.ClaimedDeliveryPoll;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
import com.chilsuitjo.back.notification.service.DeliveryPollClaimer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전달 결과 조회 대상 가져가기(#120 3-A). {@code SKIP LOCKED}와 커밋은 진짜 PostgreSQL에서만 확인되므로 Testcontainers로 돌리고,
 * 가져가기가 따로 커밋해야 해서 테스트 트랜잭션으로 감싸지 않는다. 시각은 고정 시계로 정하고, 다른 테스트가 실제 시각으로 만든 통지와 섞이지 않게 2020년을 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
class DeliveryPollClaimerTest {

  private static final String REGION = "9999999997";
  private static final Instant SENT = Instant.parse("2020-01-01T00:00:00Z");
  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final DeliveryPollSchedule SCHEDULE =
      new DeliveryPollSchedule(
          List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

  @Autowired private NoticeRepository notices;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;
  private UUID party;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES (?, '테스트', '조회동') ON CONFLICT DO NOTHING",
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
  void 시각이_된_보낸_통지만_가져가고_다음_조회_시각을_옮겨_커밋한다() {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    UUID due = notice("SENT", now);
    UUID notYet = notice("SENT", now.plusSeconds(1));
    UUID requested = notice("REQUESTED", now);
    UUID delivered = notice("DELIVERED", now);
    UUID failed = notice("FAILED", now);

    List<ClaimedDeliveryPoll> claimed = claimerAt(now).claim(50);

    assertThat(ids(claimed)).contains(due).doesNotContain(notYet, requested, delivered, failed);
    ClaimedDeliveryPoll mine =
        claimed.stream().filter(c -> c.noticeId().equals(due)).findFirst().orElseThrow();
    assertThat(mine.query().batchLeadId()).isEqualTo(due);
    assertThat(mine.query().providerMessageId()).isEqualTo("fake-" + due);
    assertThat(mine.pastDeadline()).isFalse();
    // 다른 연결에서 보이므로 커밋됐다
    assertThat(nextPollAt(due)).isEqualTo(SENT.plus(Duration.ofMinutes(5)));
    assertThat(nextPollAt(notYet)).isEqualTo(now.plusSeconds(1));
  }

  @Test
  void 가져간_통지는_옮긴_시각이_되기_전에는_다시_가져가지_않고_그_시각이_되면_다시_가져간다() {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    UUID id = notice("SENT", now);
    claimerAt(now).claim(50);

    Instant fiveMinutes = SENT.plus(Duration.ofMinutes(5));
    assertThat(ids(claimerAt(fiveMinutes.minusSeconds(1)).claim(50))).doesNotContain(id);
    assertThat(ids(claimerAt(fiveMinutes).claim(50))).contains(id);
    assertThat(nextPollAt(id)).isEqualTo(fiveMinutes.plus(Duration.ofMinutes(15)));
  }

  @Test
  void 다른_작업자가_잠근_통지는_건너뛴다() throws Exception {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    UUID locked = notice("SENT", now);
    UUID free = notice("SENT", now);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.queryForList("SELECT id FROM notice WHERE id = ? FOR UPDATE", locked);
              holding.countDown();
              await(release);
            }));
    try {
      assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

      List<UUID> claimed = ids(claimerAt(now).claim(50));

      assertThat(claimed).contains(free).doesNotContain(locked);
      assertThat(nextPollAt(locked)).isEqualTo(now);
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void 작업자_둘이_동시에_가져가도_같은_통지를_두_번_가져가지_않는다() throws Exception {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    Set<UUID> mine = new HashSet<>();
    for (int i = 0; i < 30; i++) {
      mine.add(notice("SENT", now));
    }
    CountDownLatch start = new CountDownLatch(1);
    CompletableFuture<List<UUID>> first =
        CompletableFuture.supplyAsync(() -> claimAfter(start, now, 5));
    CompletableFuture<List<UUID>> second =
        CompletableFuture.supplyAsync(() -> claimAfter(start, now, 5));
    start.countDown();
    List<UUID> all = new ArrayList<>();
    all.addAll(first.get(10, TimeUnit.SECONDS));
    all.addAll(second.get(10, TimeUnit.SECONDS));
    // 남은 것도 가져가 모두 한 번씩만 가져갔는지 본다
    all.addAll(ids(claimerAt(now).claim(100)));

    List<UUID> claimedMine = all.stream().filter(mine::contains).toList();
    assertThat(claimedMine).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(mine);
  }

  @Test
  void 가져가다_실패하면_옮긴_시각이_남지_않고_다시_가져갈_수_있다() {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    UUID first = notice("SENT", now.minusSeconds(1));
    UUID second = notice("SENT", now);
    // 잠근 줄을 모두 옮긴 뒤 같은 트랜잭션 안에서 실패하게 한다
    NoticeRepository failing = mock(NoticeRepository.class, delegatesTo(notices));
    doAnswer(
            invocation -> {
              List<Notice> rows = new ArrayList<>(notices.lockDuePolls(now, 50));
              rows.add(null);
              return rows;
            })
        .when(failing)
        .lockDuePolls(any(), anyInt());
    DeliveryPollClaimer broken =
        new DeliveryPollClaimer(
            failing, Clock.fixed(now, ZoneOffset.UTC), transactionManager, SCHEDULE);

    assertThatThrownBy(() -> broken.claim(50)).isInstanceOf(NullPointerException.class);

    assertThat(nextPollAt(first)).isEqualTo(now.minusSeconds(1));
    assertThat(nextPollAt(second)).isEqualTo(now);
    assertThat(ids(claimerAt(now).claim(50))).contains(first, second);
  }

  @Test
  void 묶음_ID가_없는_통지는_돌려주지_않고_시각만_옮겨_정상_통지를_막지_않는다() {
    Instant now = SENT.plus(Duration.ofMinutes(1));
    UUID noBatch = notice("SENT", now.minusSeconds(2));
    UUID good = notice("SENT", now);
    jdbc.update("UPDATE notice SET batch_lead_id = NULL WHERE id = ?", noBatch);

    List<UUID> claimed = ids(claimerAt(now).claim(50));

    assertThat(claimed).contains(good).doesNotContain(noBatch);
    assertThat(status(noBatch)).isEqualTo("SENT");
    assertThat(nextPollAt(noBatch)).isEqualTo(now.plus(Duration.ofMinutes(15)));
    // 잘못된 줄이 limit을 먼저 차지해도 다음 실행에서는 정상 통지가 나온다
    UUID later = notice("SENT", now);
    assertThat(ids(claimerAt(now).claim(1))).containsExactly(later);
  }

  @Test
  void 전달_확인_기한이_없는_보낸_통지는_가져가지도_바꾸지도_않고_정상_통지를_막지_않는다() {
    // V9 이전에 보냈거나 이전 버전 앱이 적은 줄: 기한을 지어내거나 실패로 끝내지 않고, 시각을 계속 옮기며 다시 가져가지도 않는다
    Instant now = SENT.plus(Duration.ofDays(4));
    UUID legacy = notice("SENT", SENT.plus(Duration.ofMinutes(1)), null);
    UUID noSentAt = notice("SENT", SENT.plus(Duration.ofMinutes(1)), null);
    jdbc.update("UPDATE notice SET sent_at = NULL WHERE id = ?", noSentAt);
    UUID good = notice("SENT", now);

    assertThat(ids(claimerAt(now).claim(1))).containsExactly(good);
    assertThat(ids(claimerAt(now.plus(Duration.ofDays(1))).claim(50)))
        .doesNotContain(legacy, noSentAt);
    for (UUID id : List.of(legacy, noSentAt)) {
      assertThat(status(id)).isEqualTo("SENT");
      assertThat(nextPollAt(id)).isEqualTo(SENT.plus(Duration.ofMinutes(1)));
      assertThat(jdbc.queryForMap("SELECT * FROM notice WHERE id = ?", id))
          .containsEntry("delivery_check_due_at", null)
          .containsEntry("delivery_poll_token", null)
          .containsEntry("failed_at", null);
    }
    assertThat(notices.countSentWithoutDeliveryCheckDue()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void 저장된_기한이_3일보다_길면_3일이_지나도_마지막_조회가_아니다() {
    // 설정을 7일로 늘린 뒤 보낸 통지: 지금 설정이나 3일로 다시 계산하지 않고 저장된 기한을 따른다
    Instant sevenDays = SENT.plus(Duration.ofDays(7));
    Instant now = SENT.plus(THREE_DAYS);
    UUID id = notice("SENT", now, sevenDays);

    List<ClaimedDeliveryPoll> claimed = claimerAt(now).claim(50);

    assertThat(claimed)
        .filteredOn(c -> c.noticeId().equals(id))
        .singleElement()
        .extracting(ClaimedDeliveryPoll::pastDeadline)
        .isEqualTo(false);
    assertThat(nextPollAt(id)).isEqualTo(now.plus(Duration.ofMinutes(15)));
  }

  @Test
  void 저장된_기한이_짧으면_그_기한에_마지막_조회가_되고_기한을_넘겨_예약하지_않는다() {
    Instant fiveMinutes = SENT.plus(Duration.ofMinutes(5));
    UUID beforeDue = notice("SENT", SENT.plus(Duration.ofMinutes(1)), fiveMinutes);
    UUID atDue = notice("SENT", fiveMinutes, fiveMinutes);

    List<ClaimedDeliveryPoll> early = claimerAt(SENT.plus(Duration.ofMinutes(1))).claim(50);
    assertThat(early)
        .filteredOn(c -> c.noticeId().equals(beforeDue))
        .singleElement()
        .extracting(ClaimedDeliveryPoll::pastDeadline)
        .isEqualTo(false);
    assertThat(nextPollAt(beforeDue)).isEqualTo(fiveMinutes);

    List<ClaimedDeliveryPoll> due = claimerAt(fiveMinutes).claim(50);
    assertThat(due)
        .filteredOn(c -> c.noticeId().equals(atDue))
        .singleElement()
        .extracting(ClaimedDeliveryPoll::pastDeadline)
        .isEqualTo(true);
  }

  @Test
  void 기한이_지난_통지는_마지막_조회로_표시하고_15분_뒤에야_다시_가져간다() {
    Instant deadline = SENT.plus(THREE_DAYS);
    UUID id = notice("SENT", deadline);

    List<ClaimedDeliveryPoll> claimed = claimerAt(deadline).claim(50);

    assertThat(claimed)
        .filteredOn(c -> c.noticeId().equals(id))
        .singleElement()
        .extracting(ClaimedDeliveryPoll::pastDeadline)
        .isEqualTo(true);
    assertThat(nextPollAt(id)).isEqualTo(deadline.plus(Duration.ofMinutes(15)));
    assertThat(ids(claimerAt(deadline.plus(Duration.ofMinutes(14))).claim(50))).doesNotContain(id);
  }

  @Test
  void 기한_직전에_가져가면_다음_조회는_기한에_잡히고_마지막_조회가_아니다() {
    Instant deadline = SENT.plus(THREE_DAYS);
    Instant now = deadline.minus(Duration.ofMinutes(5));
    UUID id = notice("SENT", now);

    List<ClaimedDeliveryPoll> claimed = claimerAt(now).claim(50);

    assertThat(claimed)
        .filteredOn(c -> c.noticeId().equals(id))
        .singleElement()
        .extracting(ClaimedDeliveryPoll::pastDeadline)
        .isEqualTo(false);
    assertThat(nextPollAt(id)).isEqualTo(deadline);
  }

  @Test
  void 다시_가져갈_때마다_적는_다음_조회_시각은_앞_세대보다_크고_DB_값과_같다() {
    Instant first = SENT.plus(Duration.ofMinutes(1));
    UUID id = notice("SENT", first);

    Instant lease1 = claimedLease(claimerAt(first), id);
    Instant lease2 = claimedLease(claimerAt(lease1), id);
    Instant lease3 = claimedLease(claimerAt(lease2), id);
    Instant lease4 = claimedLease(claimerAt(SENT.plus(THREE_DAYS)), id);
    Instant lease5 = claimedLease(claimerAt(lease4), id);

    assertThat(List.of(lease1, lease2, lease3, lease4, lease5)).isSorted().doesNotHaveDuplicates();
    assertThat(lease1).isEqualTo(SENT.plus(Duration.ofMinutes(5)));
    assertThat(nextPollAt(id)).isEqualTo(lease5);
  }

  @Test
  void 다른_조회_설정의_두번째와_이후_간격을_DB에_저장한다() {
    DeliveryPollSchedule configured =
        new DeliveryPollSchedule(
            List.of(Duration.ofMinutes(2), Duration.ofMinutes(7), Duration.ofMinutes(10)));
    Instant first = SENT.plus(Duration.ofMinutes(2));
    UUID id = notice("SENT", first);

    DeliveryPollClaimer firstClaim =
        new DeliveryPollClaimer(
            notices, Clock.fixed(first, ZoneOffset.UTC), transactionManager, configured);
    assertThat(ids(firstClaim.claim(50))).contains(id);
    assertThat(nextPollAt(id)).isEqualTo(SENT.plus(Duration.ofMinutes(7)));

    DeliveryPollClaimer secondClaim =
        new DeliveryPollClaimer(
            notices,
            Clock.fixed(SENT.plus(Duration.ofMinutes(7)), ZoneOffset.UTC),
            transactionManager,
            configured);
    assertThat(ids(secondClaim.claim(50))).contains(id);
    assertThat(nextPollAt(id)).isEqualTo(SENT.plus(Duration.ofMinutes(17)));
  }

  @Test
  void 나노초_시계로_가져가면_마이크로초로_맞춘_값을_DB와_똑같이_돌려준다() {
    Instant due = SENT.plus(Duration.ofMinutes(10));
    UUID id = notice("SENT", due);
    Instant nanos = due.plusNanos(123_456_789);

    Instant lease = claimedLease(claimerAt(nanos), id);

    assertThat(lease).isEqualTo(nanos.truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(15)));
    assertThat(nextPollAt(id)).isEqualTo(lease);
  }

  @Test
  void 마이크로초보다_짧게_이른_시각에는_다시_가져가지_않아_같은_세대_값이_다시_쓰이지_않는다() {
    // 가져간 세대가 보낸 뒤 5분이면, 그 300나노초 전 시각은 DB로 반올림하면 같은 시각이지만 아직 기한이 안 된 것으로 본다
    Instant fiveMinutes = SENT.plus(Duration.ofMinutes(5));
    UUID id = notice("SENT", fiveMinutes);

    assertThat(ids(claimerAt(fiveMinutes.minusNanos(300)).claim(50))).doesNotContain(id);
    assertThat(nextPollAt(id)).isEqualTo(fiveMinutes);
  }

  private Instant claimedLease(DeliveryPollClaimer claimer, UUID id) {
    return claimer.claim(50).stream()
        .filter(c -> c.noticeId().equals(id))
        .findFirst()
        .orElseThrow()
        .lease();
  }

  private DeliveryPollClaimer claimerAt(Instant now) {
    return new DeliveryPollClaimer(
        notices, Clock.fixed(now, ZoneOffset.UTC), transactionManager, SCHEDULE);
  }

  private List<UUID> claimAfter(CountDownLatch start, Instant now, int limit) {
    await(start);
    List<UUID> claimed = new ArrayList<>();
    List<UUID> batch;
    do {
      batch = ids(claimerAt(now).claim(limit));
      claimed.addAll(batch);
    } while (!batch.isEmpty());
    return claimed;
  }

  /** 보낸 시각은 {@link #SENT}, 보냈으면 묶음 ID는 자기 자신, 전달 확인 기한은 보낸 뒤 3일(Notice.markSent와 같음). */
  private UUID notice(String status, Instant nextPollAt) {
    return notice(status, nextPollAt, status.equals("REQUESTED") ? null : SENT.plus(THREE_DAYS));
  }

  private UUID notice(String status, Instant nextPollAt, Instant deliveryCheckDueAt) {
    UUID id = UUID.randomUUID();
    boolean sent = !status.equals("REQUESTED");
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, recipient_party_id, kind, channel, status,
          sent_at, provider_message_id, batch_lead_id, next_poll_at, delivery_check_due_at,
          created_at, version)
        VALUES (?, ?, ?, 'REQUEST', 'SMS', ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        room,
        party,
        status,
        sent ? Timestamp.from(SENT) : null,
        sent ? "fake-" + id : null,
        sent ? id : null,
        Timestamp.from(nextPollAt),
        deliveryCheckDueAt == null ? null : Timestamp.from(deliveryCheckDueAt),
        Timestamp.from(SENT.minusSeconds(10)));
    return id;
  }

  private String status(UUID id) {
    return jdbc.queryForObject("SELECT status FROM notice WHERE id = ?", String.class, id);
  }

  private Instant nextPollAt(UUID id) {
    return jdbc.queryForObject("SELECT next_poll_at FROM notice WHERE id = ?", Timestamp.class, id)
        .toInstant();
  }

  private static List<UUID> ids(List<ClaimedDeliveryPoll> claimed) {
    return claimed.stream().map(ClaimedDeliveryPoll::noticeId).toList();
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
}
