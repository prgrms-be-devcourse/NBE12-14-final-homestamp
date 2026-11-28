package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.repository.InAppNotificationRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 알림 정리를 실제 DB로 본다(04 "30일 지난 알림", 05 {@code in_app_notification}). 같은 DB를 다른 테스트와 함께 쓰므로 지운 줄
 * 수는 세지 않고, 이 테스트가 넣은 ID가 남았는지로만 본다. 기준 시각이 2001년이라 실제 시각에 만료되는 다른 테스트의 줄은 건드리지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class InAppNotificationCleanupTest {

  private static final Instant NOW = Instant.parse("2001-01-01T00:00:00.123456Z");

  @Autowired private InAppNotificationRepository notifications;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationContext context;

  private UUID member;
  private UUID other;

  @BeforeEach
  void setUp() {
    member = member();
    other = member();
  }

  @Test
  void 테스트_설정에서는_예약_정리를_만들지_않는다() {
    assertThat(context.getBeanNamesForType(InAppNotificationCleanupPoller.class)).isEmpty();
  }

  @Test
  void 만료_시각이_지났거나_같은_줄만_지우고_아직_안_지난_줄은_남긴다() {
    UUID expired = notification(member, NOW.minusSeconds(1));
    UUID boundary = notification(member, NOW);
    UUID live = notification(member, NOW.plus(1, ChronoUnit.MICROS));
    UUID othersExpired = notification(other, NOW.minusSeconds(60));

    cleanup(NOW, 500, 20).purgeExpired();

    // 목록·안 읽은 수·읽음은 만료 시각 > 지금만 본다. 지우는 기준(≤ 지금)이 그 반대라 보이는 줄은 지우지 않는다
    assertThat(exists(expired)).isFalse();
    assertThat(exists(boundary)).isFalse();
    assertThat(exists(othersExpired)).isFalse();
    assertThat(exists(live)).isTrue();
    // 알림만 지운다: 회원 행은 그대로
    assertThat(memberExists(member)).isTrue();
  }

  @Test
  void 묶음_크기와_묶음_수만큼만_지우고_남은_줄은_다음_번에_이어서_지운다() {
    UUID first = notification(member, NOW.minusSeconds(30));
    UUID second = notification(member, NOW.minusSeconds(20));
    UUID third = notification(member, NOW.minusSeconds(10));
    InAppNotificationCleanup small = cleanup(NOW, 1, 2);

    small.purgeExpired();

    // 오래된 것부터: 이번에는 두 줄만
    assertThat(exists(first)).isFalse();
    assertThat(exists(second)).isFalse();
    assertThat(exists(third)).isTrue();

    small.purgeExpired();

    assertThat(exists(third)).isFalse();
  }

  @Test
  void 묶음이_가득_차면_다음_묶음까지_지운다() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(notification(member, NOW.minusSeconds(100 - i)));
    }

    cleanup(NOW, 2, 20).purgeExpired();

    assertThat(ids).noneMatch(this::exists);
  }

  @Test
  void 다시_돌려도_같다() {
    UUID expired = notification(member, NOW.minusSeconds(1));
    InAppNotificationCleanup cleanup = cleanup(NOW, 500, 20);
    cleanup.purgeExpired();

    // 같은 기준 시각으로 다시 돌려도 오류 없이 지운 상태 그대로다
    cleanup.purgeExpired();
    assertThat(exists(expired)).isFalse();
  }

  @Test
  void 다른_작업자가_잠근_줄은_기다리지_않고_건너뛰고_다음_번에_지운다() throws Exception {
    UUID locked = notification(member, NOW.minusSeconds(30));
    UUID free = notification(member, NOW.minusSeconds(20));
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // 다른 앱의 정리 묶음처럼 한 줄을 잠근 채 트랜잭션을 열어 둔다
      Future<?> holder =
          pool.submit(() ->
              transaction.executeWithoutResult(status -> {
                jdbc.queryForObject(
                    "SELECT id FROM state.in_app_notification WHERE id = ? FOR UPDATE",
                    UUID.class,
                    locked);
                holding.countDown();
                await(release);
              }));
      assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

      // 잠금을 기다리면 이 get이 시간 초과로 실패한다
      pool.submit(() -> cleanup(NOW, 500, 20).purgeExpired()).get(10, TimeUnit.SECONDS);

      assertThat(exists(free)).isFalse();
      assertThat(exists(locked)).isTrue();

      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    cleanup(NOW, 500, 20).purgeExpired();

    assertThat(exists(locked)).isFalse();
  }

  @Test
  void 중간_묶음이_실패하면_앞_묶음은_남고_남은_줄은_다음_번에_지운다() {
    UUID first = notification(member, NOW.minusSeconds(30));
    UUID second = notification(member, NOW.minusSeconds(20));
    // JPA 저장소 프록시는 spy가 안 돼, 진짜 저장소로 넘기는 대역에서 두 번째 묶음만 실패시킨다
    InAppNotificationRepository failing =
        mock(InAppNotificationRepository.class, delegatesTo(notifications));
    Answer<Integer> real = invocation -> notifications.deleteExpired(NOW, 1);
    doAnswer(real)
        .doThrow(new IllegalStateException("DB 오류"))
        .doAnswer(real)
        .when(failing)
        .deleteExpired(NOW, 1);
    InAppNotificationCleanup flaky =
        new InAppNotificationCleanup(failing, transaction, Clock.fixed(NOW, ZoneOffset.UTC), 1, 20);

    assertThatThrownBy(flaky::purgeExpired).isInstanceOf(IllegalStateException.class);

    // 첫 묶음은 커밋됐고 실패한 묶음은 아무것도 지우지 않았다
    assertThat(exists(first)).isFalse();
    assertThat(exists(second)).isTrue();

    flaky.purgeExpired();

    assertThat(exists(second)).isFalse();
  }

  @Test
  void 묶음_설정이_1보다_작으면_만들지_않는다() {
    assertThatThrownBy(() -> cleanup(NOW, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cleanup(NOW, 1, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private InAppNotificationCleanup cleanup(Instant now, int batchSize, int maxBatches) {
    return new InAppNotificationCleanup(
        notifications, transaction, Clock.fixed(now, ZoneOffset.UTC), batchSize, maxBatches);
  }

  private UUID notification(UUID memberId, Instant expiresAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.in_app_notification (id, member_id, kind, topic, expires_at, created_at, version)
        VALUES (?, ?, 'SERVICE', 'PHONE_VERIFICATION_RELEASED', ?, ?, 0)
        """,
        id,
        memberId,
        Timestamp.from(expiresAt),
        Timestamp.from(expiresAt.minus(30, ChronoUnit.DAYS)));
    return id;
  }

  private boolean exists(UUID id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM state.in_app_notification WHERE id = ?", Integer.class, id)
        == 1;
  }

  private boolean memberExists(UUID id) {
    return jdbc.queryForObject("SELECT count(*) FROM state.member WHERE id = ?", Integer.class, id)
        == 1;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }
}
