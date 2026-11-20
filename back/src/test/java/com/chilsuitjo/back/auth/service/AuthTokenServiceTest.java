package com.chilsuitjo.back.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.entity.RefreshToken;
import com.chilsuitjo.back.auth.repository.RefreshTokenRepository;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 ACC-2a~c, 불변식 12. 실제 PostgreSQL에서 로테이션·유예·재사용 감지·로그아웃과 같은 토큰의 동시 재발급을 본다. 시각은 바꿀 수 있는 시계로 옮긴다.
 */
@Import({TestcontainersConfiguration.class, AuthTokenServiceTest.TestClock.class})
@SpringBootTest
@ActiveProfiles("test")
class AuthTokenServiceTest {

  private static final Instant START = Instant.parse("2026-11-20T01:00:00Z");
  private static final Duration GRACE = Duration.ofSeconds(10);
  private static final Duration REFRESH_TTL = Duration.ofDays(14);

  @Autowired private AuthTokenService service;
  @Autowired private RefreshTokenRepository tokens;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;
  @Autowired private MutableClock clock;

  private final ExecutorService pool = Executors.newFixedThreadPool(3);
  private final List<UUID> members = new ArrayList<>();

  private UUID memberId;

  @BeforeEach
  void setUp() {
    clock.set(START);
    memberId = newMember();
  }

  @AfterEach
  void clean() {
    pool.shutdownNow();
    jdbc.update("DELETE FROM state.refresh_token");
    for (UUID id : members) {
      jdbc.update("DELETE FROM state.member WHERE id = ?", id);
    }
  }

  @Test
  void 로그인하면_새_묶음의_토큰을_받고_DB에는_해시만_남는다() {
    IssuedTokens issued = service.issue(memberId);

    RefreshToken row = onlyRow();
    assertThat(row.getTokenHash())
        .isEqualTo(RefreshTokenKeys.hash(issued.refreshToken()))
        .isNotEqualTo(issued.refreshToken());
    assertThat(row.getExpiresAt()).isEqualTo(START.plus(REFRESH_TTL));
    assertThat(issued.refreshMaxAge()).isEqualTo(REFRESH_TTL);
    assertThat(accessTokens.read(issued.accessToken()))
        .contains(new AccessTokens.Claims(memberId, row.getFamilyId()));
    assertThat(issued.toString()).doesNotContain(issued.refreshToken(), issued.accessToken());
  }

  @Test
  void 재발급하면_다음_토큰으로_바꾸고_이전_토큰은_새_행을_가리키며_폐기된다() {
    IssuedTokens login = service.issue(memberId);
    clock.advance(Duration.ofHours(1));

    IssuedTokens next = service.refresh(login.refreshToken());

    assertThat(next.refreshToken()).isNotEqualTo(login.refreshToken());
    RefreshToken old = row(login.refreshToken());
    RefreshToken current = row(next.refreshToken());
    assertThat(old.getRevokedAt()).isEqualTo(clock.instant());
    assertThat(old.getReplacedById()).isEqualTo(current.getId());
    assertThat(current.getFamilyId()).isEqualTo(old.getFamilyId());
    assertThat(current.getRevokedAt()).isNull();
    assertThat(current.getExpiresAt()).isEqualTo(clock.instant().plus(REFRESH_TTL));
    assertThat(accessTokens.read(next.accessToken()))
        .contains(new AccessTokens.Claims(memberId, old.getFamilyId()));
  }

  @Test
  void ACC_2b_유예_시간_안에_같은_R이_다시_오면_이미_발급한_R프라임을_돌려준다() {
    IssuedTokens login = service.issue(memberId);
    IssuedTokens first = service.refresh(login.refreshToken());
    clock.advance(GRACE);

    IssuedTokens again = service.refresh(login.refreshToken());

    assertThat(again.refreshToken()).isEqualTo(first.refreshToken());
    assertThat(again.refreshMaxAge()).isEqualTo(REFRESH_TTL.minus(GRACE));
    assertThat(tokens.count()).isEqualTo(2);
    assertThat(row(first.refreshToken()).getRevokedAt()).isNull();
  }

  @Test
  void ACC_2c_유예가_지난_뒤_R을_다시_쓰면_REFRESH_REUSED이고_묶음_전체가_폐기된다() {
    IssuedTokens login = service.issue(memberId);
    IssuedTokens first = service.refresh(login.refreshToken());
    IssuedTokens otherDevice = service.issue(memberId);
    clock.advance(GRACE.plusMillis(1));

    expectError(() -> service.refresh(login.refreshToken()), ErrorCode.REFRESH_REUSED);

    // 예외를 던져도 폐기는 커밋된다
    assertThat(revokedAtInDb(first.refreshToken())).isNotNull();
    assertThat(revokedAtInDb(otherDevice.refreshToken())).isNull();
    expectError(() -> service.refresh(first.refreshToken()), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 유예_안이어도_R프라임이_이미_폐기됐으면_LOGIN_REQUIRED이고_묶음은_그대로다() {
    IssuedTokens login = service.issue(memberId);
    IssuedTokens first = service.refresh(login.refreshToken());
    IssuedTokens second = service.refresh(first.refreshToken());
    clock.advance(Duration.ofSeconds(5));

    expectError(() -> service.refresh(login.refreshToken()), ErrorCode.LOGIN_REQUIRED);

    assertThat(revokedAtInDb(second.refreshToken())).isNull();
    assertThat(service.refresh(second.refreshToken()).refreshToken()).isNotBlank();
  }

  @Test
  void 유예_안이어도_R프라임의_해시가_맞지_않으면_LOGIN_REQUIRED다() {
    IssuedTokens login = service.issue(memberId);
    IssuedTokens first = service.refresh(login.refreshToken());
    // 키를 바꾼 것과 같은 상황: 다시 계산한 R'가 저장된 해시와 다르다
    jdbc.update(
        "UPDATE state.refresh_token SET token_hash = ? WHERE token_hash = ?",
        RefreshTokenKeys.hash("다른 토큰"),
        RefreshTokenKeys.hash(first.refreshToken()));

    expectError(() -> service.refresh(login.refreshToken()), ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void 모르는_토큰_빈_토큰_만료된_토큰은_LOGIN_REQUIRED다() {
    IssuedTokens login = service.issue(memberId);

    expectError(() -> service.refresh("모르는-토큰"), ErrorCode.LOGIN_REQUIRED);
    expectError(() -> service.refresh(null), ErrorCode.LOGIN_REQUIRED);
    expectError(() -> service.refresh(" "), ErrorCode.LOGIN_REQUIRED);

    clock.advance(REFRESH_TTL);
    expectError(() -> service.refresh(login.refreshToken()), ErrorCode.LOGIN_REQUIRED);
    assertThat(revokedAtInDb(login.refreshToken())).isNull();
  }

  @Test
  void 로그아웃하면_그_묶음만_폐기되고_그_토큰은_다시_쓸_수_없다() {
    IssuedTokens login = service.issue(memberId);
    IssuedTokens next = service.refresh(login.refreshToken());
    IssuedTokens otherDevice = service.issue(memberId);

    service.logout(memberId, familyOf(next));

    assertThat(revokedAtInDb(next.refreshToken())).isNotNull();
    assertThat(revokedAtInDb(otherDevice.refreshToken())).isNull();
    expectError(() -> service.refresh(next.refreshToken()), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 남의_묶음으로_로그아웃하면_아무것도_바꾸지_않는다() {
    IssuedTokens login = service.issue(memberId);

    service.logout(newMember(), familyOf(login));

    assertThat(revokedAtInDb(login.refreshToken())).isNull();
  }

  @Test
  void 같은_R로_동시에_재발급해도_한_번만_바꾸고_둘_다_같은_R프라임을_받는다() throws Exception {
    IssuedTokens login = service.issue(memberId);
    String r = login.refreshToken();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    // 테스트가 R 행을 먼저 잠가 두 요청이 모두 같은 잠금을 기다리게 한 뒤 놓는다
    Future<?> holder =
        pool.submit(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.queryForList(
                  "SELECT id FROM state.refresh_token WHERE token_hash = ? FOR UPDATE",
                  RefreshTokenKeys.hash(r));
              locked.countDown();
              await(release);
            }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    Future<IssuedTokens> a = pool.submit(() -> service.refresh(r));
    Future<IssuedTokens> b = pool.submit(() -> service.refresh(r));
    waitForLockWaiters(2);
    release.countDown();
    holder.get(10, TimeUnit.SECONDS);

    IssuedTokens resultA = a.get(10, TimeUnit.SECONDS);
    IssuedTokens resultB = b.get(10, TimeUnit.SECONDS);

    assertThat(resultA.refreshToken()).isEqualTo(resultB.refreshToken());
    assertThat(tokens.count()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.refresh_token WHERE revoked_at IS NOT NULL",
                Integer.class))
        .isEqualTo(1);
    assertThat(revokedAtInDb(resultA.refreshToken())).isNull();
  }

  private UUID newMember() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', ?, 0)",
        id,
        Timestamp.from(START));
    members.add(id);
    return id;
  }

  private RefreshToken onlyRow() {
    assertThat(tokens.count()).isEqualTo(1);
    return tokens.findAll().getFirst();
  }

  private RefreshToken row(String token) {
    String hash = RefreshTokenKeys.hash(token);
    return tokens.findAll().stream()
        .filter(t -> t.getTokenHash().equals(hash))
        .findFirst()
        .orElseThrow();
  }

  private Timestamp revokedAtInDb(String token) {
    return jdbc.queryForObject(
        "SELECT revoked_at FROM state.refresh_token WHERE token_hash = ?",
        Timestamp.class,
        RefreshTokenKeys.hash(token));
  }

  private UUID familyOf(IssuedTokens issued) {
    return accessTokens.read(issued.accessToken()).orElseThrow().familyId();
  }

  /** 행 잠금을 기다리는 연결이 {@code count}개가 될 때까지 기다린다. */
  private void waitForLockWaiters(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting >= count) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("재발급 요청 " + count + "개가 잠금을 기다리지 않았다");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("잠금을 놓으라는 신호가 오지 않았다");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void expectError(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(code);
  }

  /** 테스트에서 옮길 수 있는 시계. */
  static final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    void set(Instant instant) {
      now.set(instant);
    }

    void advance(Duration duration) {
      now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }
}
