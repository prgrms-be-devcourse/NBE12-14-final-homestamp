package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.dto.InfoCommand;
import com.chilsuitjo.back.notification.rule.InAppTopic;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴한 회원의 서비스 안 알림(#424): 지우기는 받은 사람 것만, 탈퇴한 회원에게는 만들지 않음, 알림 저장과 탈퇴가 겹쳐도 탈퇴 뒤 알림이 남지 않음. 탈퇴 쪽은
 * #432 전이라 탈퇴 트랜잭션의 모양(회원 행 쓰기 잠금 → 상태 바꿈 → {@link MemberNotifications#deleteAllOf})을 이 테스트가 직접
 * 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberNotificationsTest {

  private static final InAppTopic TOPIC = InAppTopic.PHONE_VERIFICATION_RELEASED;

  @Autowired private MemberNotifications memberNotifications;
  @Autowired private InAppNotifier notifier;
  @Autowired private ActiveRecipients recipients;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void 지우기는_그_회원이_받은_알림만_지우고_수를_돌려준다() {
    UUID member = member();
    UUID other = member();
    notifyIn(member);
    notifyIn(member);
    notifyIn(other);

    int deleted = transaction.execute(s -> memberNotifications.deleteAllOf(member));

    assertThat(deleted).isEqualTo(2);
    assertThat(count(member)).isZero();
    assertThat(count(other)).isEqualTo(1);
  }

  @Test
  void 탈퇴한_회원에게는_알림을_만들지_않는다() {
    UUID member = member();
    withdrawNow(member);

    notifyIn(member);

    assertThat(count(member)).isZero();
    assertThat(transaction.<Boolean>execute(s -> recipients.holdActive(member))).isFalse();
    assertThat(transaction.<Boolean>execute(s -> recipients.holdActive(UUID.randomUUID())))
        .isFalse();
  }

  @Test
  void 트랜잭션_밖에서_부르면_개발_오류다() {
    UUID member = member();
    assertThatThrownBy(() -> memberNotifications.deleteAllOf(member))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> recipients.holdActive(member))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void 탈퇴가_되돌아가면_알림도_그대로다() {
    UUID member = member();
    notifyIn(member);

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(s -> {
              withdrawIn(member);
              throw new IllegalStateException("탈퇴 실패");
            }))
        .hasMessage("탈퇴 실패");

    assertThat(count(member)).isEqualTo(1);
    assertThat(status(member)).isEqualTo("ACTIVE");
  }

  @Test
  void 알림_저장이_먼저_잡으면_탈퇴는_기다렸다가_그_알림까지_지운다() throws Exception {
    UUID member = member();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> notify =
        pool.submit(() ->
            transaction.executeWithoutResult(s -> {
              notifier.notify(InfoCommand.account(member, TOPIC));
              held.countDown();
              await(release);
            }));
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    Future<?> withdraw =
        pool.submit(() -> transaction.executeWithoutResult(s -> withdrawIn(member)));
    awaitBlocked();

    release.countDown();
    notify.get(10, TimeUnit.SECONDS);
    withdraw.get(10, TimeUnit.SECONDS);

    assertThat(status(member)).isEqualTo("WITHDRAWN");
    assertThat(count(member)).isZero();
  }

  @Test
  void 탈퇴가_먼저_잡으면_알림_저장은_기다렸다가_만들지_않는다() throws Exception {
    UUID member = member();
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> withdraw =
        pool.submit(() ->
            transaction.executeWithoutResult(s -> {
              withdrawIn(member);
              held.countDown();
              await(release);
            }));
    assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
    Future<?> notify =
        pool.submit(() ->
            transaction.executeWithoutResult(
                s -> notifier.notify(InfoCommand.account(member, TOPIC))));
    awaitBlocked();

    release.countDown();
    withdraw.get(10, TimeUnit.SECONDS);
    notify.get(10, TimeUnit.SECONDS);

    assertThat(count(member)).isZero();
  }

  /** 탈퇴 트랜잭션의 모양(#432 {@code once}): 회원 행 쓰기 잠금 → 탈퇴 상태 → 그 회원의 알림 삭제. */
  private void withdrawIn(UUID member) {
    jdbc.queryForObject("SELECT id FROM state.member WHERE id = ? FOR UPDATE", UUID.class, member);
    jdbc.update(
        "UPDATE state.member SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP WHERE id = ?",
        member);
    memberNotifications.deleteAllOf(member);
  }

  private void withdrawNow(UUID member) {
    transaction.executeWithoutResult(s -> withdrawIn(member));
  }

  private void notifyIn(UUID member) {
    transaction.executeWithoutResult(s -> notifier.notify(InfoCommand.account(member, TOPIC)));
  }

  /** 다른 쪽이 회원 행 잠금을 기다리기 시작할 때까지 기다린다. */
  private void awaitBlocked() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                  + " AND wait_event_type = 'Lock' AND query ILIKE '%state.member%'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("회원 행 잠금을 기다리는 트랜잭션이 없음");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("놓기 신호가 오지 않음");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private int count(UUID member) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.in_app_notification WHERE member_id = ?",
        Integer.class,
        member);
  }

  private String status(UUID member) {
    return jdbc.queryForObject(
        "SELECT status FROM state.member WHERE id = ?", String.class, member);
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
