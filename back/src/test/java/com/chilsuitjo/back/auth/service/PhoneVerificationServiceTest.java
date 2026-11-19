package com.chilsuitjo.back.auth.service;

import static com.chilsuitjo.back.auth.rule.VerificationPurpose.CHANGE;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.FIND_ID;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.RESET;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.SIGNUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.dto.VerificationTicket;
import com.chilsuitjo.back.auth.entity.PhoneVerification;
import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 06 ACC-1e~l. 실제 PostgreSQL에서 번호 잠금·한 줄 갱신·동시 요청까지 본다. 시각은 바꿀 수 있는 시계로 옮긴다. */
@Import({TestcontainersConfiguration.class, PhoneVerificationServiceTest.TestClock.class})
@SpringBootTest
class PhoneVerificationServiceTest {

  /** 한국 시간 2026-11-19 10:00. */
  private static final Instant START = Instant.parse("2026-11-19T01:00:00Z");

  private static final String PHONE = "01012345678";
  private static final Duration RESEND = Duration.ofSeconds(61);

  @Autowired private PhoneVerificationService service;
  @Autowired private PhoneVerificationRepository verifications;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;
  @Autowired private MutableClock clock;
  @Autowired private VerificationKeys keys;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM state.phone_verification");
    jdbc.update("DELETE FROM state.rate_counter");
    clock.set(START);
  }

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  // ACC-1e 인증번호는 발급부터 5분, 5번까지, 하루 5번 보낸다

  @Test
  void 맞는_번호로_인증하면_인증_표를_받는다() {
    IssuedCode code = service.issue("010-1234-5678", SIGNUP);

    VerificationTicket ticket = service.verify(code.verificationId(), code.code());

    assertThat(code.phone()).isEqualTo(PHONE);
    assertThat(code.code()).matches("\\d{6}");
    assertThat(ticket.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
    assertThat(row(code).getVerifiedAt()).isEqualTo(START);
  }

  @Test
  void 인증번호는_발급부터_5분이_지나면_CODE_EXPIRED() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofMinutes(5));

    assertCode(() -> service.verify(code.verificationId(), code.code()), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 틀리면_CODE_INVALID이고_허용_횟수가_남았으면_다시_넣을_수_있다() {
    IssuedCode code = service.issue(PHONE, SIGNUP);

    for (int i = 0; i < 4; i++) {
      assertCode(() -> service.verify(code.verificationId(), wrong(code)), ErrorCode.CODE_INVALID);
    }

    assertThat(row(code).getAttemptCount()).isEqualTo(4);
    assertThat(service.verify(code.verificationId(), code.code())).isNotNull();
  }

  @Test
  void 다섯_번_틀리면_맞는_번호도_쓸_수_없다() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    for (int i = 0; i < 5; i++) {
      assertCode(() -> service.verify(code.verificationId(), wrong(code)), ErrorCode.CODE_INVALID);
    }

    assertCode(() -> service.verify(code.verificationId(), code.code()), ErrorCode.CODE_EXPIRED);
    assertThat(row(code).getAttemptCount()).isEqualTo(5);
  }

  @Test
  void 같은_번호로_하루_다섯_번을_넘게_받으면_목적과_상관없이_DAILY_LIMIT() {
    service.issue(PHONE, SIGNUP);
    service.issue(PHONE, RESET);
    service.issue(PHONE, FIND_ID);
    service.issue(PHONE, CHANGE);
    clock.advance(RESEND);
    service.issue(PHONE, SIGNUP);
    clock.advance(RESEND);

    assertCode(() -> service.issue(PHONE, RESET), ErrorCode.DAILY_LIMIT);
    assertThat(verifications.count()).isEqualTo(5);
  }

  @Test
  void 하루는_한국_날짜로_센다() {
    clock.set(Instant.parse("2026-11-19T14:50:00Z")); // 한국 23:50
    for (int i = 0; i < 5; i++) {
      service.issue(PHONE, SIGNUP);
      clock.advance(RESEND);
    }
    assertCode(() -> service.issue(PHONE, SIGNUP), ErrorCode.DAILY_LIMIT);

    clock.set(Instant.parse("2026-11-19T15:00:00Z")); // 한국 다음 날 00:00

    assertThat(service.issue(PHONE, SIGNUP)).isNotNull();
  }

  @Test
  void 번호_모양이_틀리면_VALIDATION_FAILED() {
    assertCode(() -> service.issue("02-123-4567", SIGNUP), ErrorCode.VALIDATION_FAILED);
  }

  // ACC-1g 다시 받으면 이전 번호는 쓸 수 없다

  @Test
  void 다시_받으면_이전_번호는_CODE_REPLACED이고_틀린_횟수는_그대로다() {
    IssuedCode first = service.issue(PHONE, SIGNUP);
    clock.advance(RESEND);
    IssuedCode second = service.issue("010-1234-5678", SIGNUP);
    assumeThat(first.code()).isNotEqualTo(second.code());

    // A를 받은 화면(A의 id)에서는 무엇을 넣어도
    assertCode(() -> service.verify(first.verificationId(), first.code()), ErrorCode.CODE_REPLACED);
    assertCode(
        () -> service.verify(first.verificationId(), wrong(first, second)),
        ErrorCode.CODE_REPLACED);
    // B를 받은 화면에 늦게 온 A를 넣으면
    assertCode(
        () -> service.verify(second.verificationId(), first.code()), ErrorCode.CODE_REPLACED);

    assertThat(row(first).getAttemptCount()).isZero();
    assertThat(row(second).getAttemptCount()).isZero();
    assertThat(service.verify(second.verificationId(), second.code())).isNotNull();
  }

  @Test
  void 목적이_다르면_서로_무효로_만들지_않는다() {
    IssuedCode signup = service.issue(PHONE, SIGNUP);
    IssuedCode reset = service.issue(PHONE, RESET);

    assertThat(service.verify(signup.verificationId(), signup.code())).isNotNull();
    assertThat(service.verify(reset.verificationId(), reset.code())).isNotNull();
  }

  @Test
  void 없는_진행이나_이미_인증한_번호는_CODE_EXPIRED() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    service.verify(code.verificationId(), code.code());

    assertCode(() -> service.verify(code.verificationId(), code.code()), ErrorCode.CODE_EXPIRED);
    assertCode(() -> service.verify(UUID.randomUUID(), code.code()), ErrorCode.CODE_EXPIRED);
  }

  // ACC-1h 동시에 받아도 쓸 수 있는 번호는 하나다

  @Test
  void 같은_번호_목적으로_동시에_받아도_인증번호는_하나다() throws Exception {
    List<Object> results =
        together(() -> service.issue(PHONE, SIGNUP), () -> service.issue(PHONE, SIGNUP));

    assertThat(results).filteredOn(IssuedCode.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(BusinessException.class::isInstance)
        .extracting(e -> ((BusinessException) e).errorCode())
        .containsExactly(ErrorCode.RATE_LIMITED);
    assertThat(verifications.count()).isEqualTo(1);
    assertThat(smsTotalToday()).isEqualTo(1);
  }

  @Test
  void 목적이_다른_요청이_동시에_와도_하루_횟수를_넘지_않는다() throws Exception {
    service.issue(PHONE, SIGNUP);
    service.issue(PHONE, RESET);
    service.issue(PHONE, FIND_ID);
    service.issue(PHONE, CHANGE);
    clock.advance(RESEND);

    List<Object> results =
        together(() -> service.issue(PHONE, SIGNUP), () -> service.issue(PHONE, RESET));

    assertThat(results).filteredOn(IssuedCode.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(BusinessException.class::isInstance)
        .extracting(e -> ((BusinessException) e).errorCode())
        .containsExactly(ErrorCode.DAILY_LIMIT);
    assertThat(verifications.count()).isEqualTo(5);
  }

  // ACC-1i 다시 받기는 verify.resend.interval에 한 번이다

  @Test
  void 간격_안에_다시_받으면_RATE_LIMITED와_남은_시간() {
    service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofSeconds(20));

    assertThatThrownBy(() -> service.issue(PHONE, SIGNUP))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.retryAfter()).contains(Duration.ofSeconds(40));
            });
  }

  @Test
  void 직전_발송이_실패했으면_바로_다시_받을_수_있다() {
    IssuedCode failed = service.issue(PHONE, SIGNUP);
    service.forgetFailedSend(failed.verificationId());

    IssuedCode again = service.issue(PHONE, SIGNUP);

    assertThat(verifications.findAll())
        .extracting(PhoneVerification::getId)
        .containsExactly(again.verificationId());
  }

  // ACC-1j 발송이 실패하면 세지 않고, 결과를 모르면 보낸 것으로 본다

  @Test
  void 발송이_실패한_번호는_하루_횟수에_세지_않고_전체_한도는_되돌리지_않는다() {
    for (int i = 0; i < 5; i++) {
      service.forgetFailedSend(service.issue(PHONE, SIGNUP).verificationId());
    }

    IssuedCode code = service.issue(PHONE, SIGNUP);

    assertThat(service.verify(code.verificationId(), code.code())).isNotNull();
    assertThat(smsTotalToday()).isEqualTo(6);
  }

  @Test
  void 다시_받기가_실패하면_이전_번호는_CODE_EXPIRED다() {
    IssuedCode first = service.issue(PHONE, SIGNUP);
    clock.advance(RESEND);
    service.forgetFailedSend(service.issue(PHONE, SIGNUP).verificationId());

    assertCode(() -> service.verify(first.verificationId(), first.code()), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 인증한_행은_발송_실패로_지우지_않는다() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    service.verify(code.verificationId(), code.code());

    service.forgetFailedSend(code.verificationId());
    service.forgetFailedSend(UUID.randomUUID());

    assertThat(verifications.findById(code.verificationId())).isPresent();
  }

  // ACC-1k 서비스 전체 한도(sms.daily.total)

  @Test
  void 전체_문자_한도가_다_찼으면_DAILY_LIMIT이고_행을_만들지_않는다() {
    fillSmsTotal(40);

    assertCode(() -> service.issue(PHONE, SIGNUP), ErrorCode.DAILY_LIMIT);
    assertThat(verifications.count()).isZero();
  }

  @Test
  void 다른_번호의_요청이_동시에_와도_전체_한도를_넘지_않는다() throws Exception {
    fillSmsTotal(39);

    List<Object> results =
        together(
            () -> service.issue("01011112222", SIGNUP), () -> service.issue("01033334444", SIGNUP));

    assertThat(results).filteredOn(IssuedCode.class::isInstance).hasSize(1);
    assertThat(smsTotalToday()).isEqualTo(40);
    assertThat(verifications.count()).isEqualTo(1);
  }

  // ACC-1f 인증 표는 용도가 맞는 작업이 성공할 때 한 번만 쓰인다

  @Test
  void 인증_표는_작업이_성공하면_한_번만_쓰인다() {
    String ticket = ticket(SIGNUP);

    assertThat(inTransaction(() -> service.useTicket(ticket, SIGNUP))).isEqualTo(PHONE);
    assertCode(
        () -> inTransaction(() -> service.useTicket(ticket, SIGNUP)), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 작업이_실패해_롤백되면_표는_그대로_남는다() {
    String ticket = ticket(SIGNUP);

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              service.useTicket(ticket, SIGNUP);
              throw new BusinessException(ErrorCode.EMAIL_TAKEN);
            }))
        .isInstanceOf(BusinessException.class);

    assertThat(inTransaction(() -> service.useTicket(ticket, SIGNUP))).isEqualTo(PHONE);
  }

  @Test
  void 같은_표로_두_요청이_동시에_와도_하나만_성공한다() throws Exception {
    String ticket = ticket(SIGNUP);
    CountDownLatch firstUsed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Future<Object> first =
        pool.submit(() ->
            outcome(() ->
                transaction.execute(status -> {
                  String phone = service.useTicket(ticket, SIGNUP);
                  firstUsed.countDown();
                  await(release);
                  return phone;
                })));
    await(firstUsed);
    Future<Object> second =
        pool.submit(() -> outcome(() -> inTransaction(() -> service.useTicket(ticket, SIGNUP))));
    awaitLockWaiter(); // 두 번째 요청이 같은 행에서 기다리는지 DB로 확인한다
    release.countDown();

    assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(PHONE);
    assertThat(second.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
  }

  @Test
  void 목적이_다른_작업에는_쓸_수_없다() {
    String ticket = ticket(SIGNUP);

    assertCode(() -> inTransaction(() -> service.useTicket(ticket, RESET)), ErrorCode.CODE_EXPIRED);
    assertCode(() -> service.readTicket(ticket, FIND_ID), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 서명이_없거나_틀린_표는_CODE_EXPIRED() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    service.verify(code.verificationId(), code.code());
    String rowIdOnly = code.verificationId().toString();

    assertCode(
        () -> inTransaction(() -> service.useTicket(rowIdOnly, SIGNUP)), ErrorCode.CODE_EXPIRED);
    assertCode(
        () -> inTransaction(() -> service.useTicket(rowIdOnly + ".AAAA", SIGNUP)),
        ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 인증하지_않은_진행의_표는_쓸_수_없다() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    String unverified = keys.signTicket(code.verificationId());

    assertCode(
        () -> inTransaction(() -> service.useTicket(unverified, SIGNUP)), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 아이디_찾기_표는_보기만_해서_유효_시간_안에_다시_쓸_수_있다() {
    String ticket = ticket(FIND_ID);

    assertThat(service.readTicket(ticket, FIND_ID)).isEqualTo(PHONE);
    assertThat(service.readTicket(ticket, FIND_ID)).isEqualTo(PHONE);

    clock.advance(Duration.ofMinutes(15));
    assertCode(() -> service.readTicket(ticket, FIND_ID), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 쓰는_법이_목적과_맞지_않으면_코드_실수다() {
    assertThatThrownBy(() -> inTransaction(() -> service.useTicket("t", FIND_ID)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.readTicket("t", SIGNUP))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.useTicket("t", SIGNUP))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  // ACC-1l 인증 표는 인증한 때부터 verify.ticket.ttl 동안 쓴다

  @Test
  void 만료_10초_전에_인증해도_표는_인증한_때부터_15분_쓴다() {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofMinutes(5).minusSeconds(10));
    String ticket = service.verify(code.verificationId(), code.code()).ticket();

    clock.advance(Duration.ofMinutes(15).minusSeconds(1));

    assertThat(inTransaction(() -> service.useTicket(ticket, SIGNUP))).isEqualTo(PHONE);
  }

  @Test
  void 인증하고_15분이_지나면_CODE_EXPIRED() {
    String ticket = ticket(SIGNUP);
    clock.advance(Duration.ofMinutes(15));

    assertCode(
        () -> inTransaction(() -> service.useTicket(ticket, SIGNUP)), ErrorCode.CODE_EXPIRED);
  }

  // 잠금을 기다리는 동안 시간이 지나면 잠금을 얻은 뒤의 시각으로 판단한다(PR #91 리뷰)

  @Test
  void 행_잠금을_기다리는_동안_인증번호가_만료되면_CODE_EXPIRED() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofMinutes(5).minusSeconds(1));
    CountDownLatch release = holdLock(() -> lockRow(code.verificationId()));

    Future<Object> waiting =
        pool.submit(() -> outcome(() -> service.verify(code.verificationId(), code.code())));
    awaitLockWaiter();
    clock.advance(Duration.ofSeconds(1));
    release.countDown();

    assertThat(waiting.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
    assertThat(row(code).getVerifiedAt()).isNull();
  }

  @Test
  void 번호_잠금을_기다리는_동안_한국_날짜가_바뀌면_바뀐_날짜로_센다() throws Exception {
    clock.set(Instant.parse("2026-11-19T14:50:00Z")); // 한국 23:50
    for (int i = 0; i < 5; i++) {
      service.issue(PHONE, SIGNUP);
      clock.advance(RESEND);
    }
    clock.set(Instant.parse("2026-11-19T14:59:59Z")); // 한국 23:59:59, 오늘 5번을 다 씀
    CountDownLatch release =
        holdLock(() ->
            jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, keys.phoneLockKey(PHONE)));

    Future<Object> waiting = pool.submit(() -> outcome(() -> service.issue(PHONE, SIGNUP)));
    awaitLockWaiter();
    Instant afterLock = Instant.parse("2026-11-19T15:00:30Z"); // 한국 다음 날 00:00:30
    clock.set(afterLock);
    release.countDown();

    Object result = waiting.get(10, TimeUnit.SECONDS);
    assertThat(result).isInstanceOf(IssuedCode.class);
    PhoneVerification created = row((IssuedCode) result);
    assertThat(created.getCreatedAt()).isEqualTo(afterLock);
    assertThat(created.getExpiresAt()).isEqualTo(afterLock.plus(Duration.ofMinutes(5)));
    assertThat(smsTotalOn(LocalDate.of(2026, 11, 20))).isEqualTo(1);
  }

  @Test
  void 표_행_잠금을_기다리는_동안_만료되면_쓸_수_없다() throws Exception {
    String ticket = ticket(SIGNUP);
    UUID id = UUID.fromString(ticket.substring(0, ticket.indexOf('.')));
    clock.advance(Duration.ofMinutes(15).minusSeconds(1));
    CountDownLatch release = holdLock(() -> lockRow(id));

    Future<Object> waiting =
        pool.submit(() -> outcome(() -> inTransaction(() -> service.useTicket(ticket, SIGNUP))));
    awaitLockWaiter();
    clock.advance(Duration.ofSeconds(1));
    release.countDown();

    assertThat(waiting.get(10, TimeUnit.SECONDS))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
    assertThat(verifications.findById(id).orElseThrow().getConsumedAt()).isNull();
  }

  @Test
  void 전체_한도_줄을_기다리는_동안_한국_날짜가_바뀌면_바뀐_날짜로_다시_센다() throws Exception {
    LocalDate oldDay = LocalDate.of(2026, 11, 19);
    LocalDate newDay = LocalDate.of(2026, 11, 20);
    clock.set(Instant.parse("2026-11-19T14:50:00Z")); // 한국 23:50
    for (int i = 0; i < 4; i++) {
      service.issue(PHONE, SIGNUP);
      clock.advance(RESEND);
    }
    clock.set(Instant.parse("2026-11-19T14:59:59Z")); // 한국 23:59:59
    // 다른 번호의 요청이 오늘 sms-total 줄을 잡고 있다
    CountDownLatch release =
        holdLock(() ->
            jdbc.queryForList(
                "SELECT count FROM state.rate_counter"
                    + " WHERE counter_key = 'sms-total' AND window_date = ? FOR UPDATE",
                java.sql.Date.valueOf(oldDay)));

    Future<Object> waiting = pool.submit(() -> outcome(() -> service.issue(PHONE, SIGNUP)));
    awaitLockWaiter();
    Instant afterLock = Instant.parse("2026-11-19T15:00:30Z"); // 한국 다음 날 00:00:30
    clock.set(afterLock);
    release.countDown();

    Object result = waiting.get(10, TimeUnit.SECONDS);
    assertThat(result).isInstanceOf(IssuedCode.class);
    PhoneVerification created = row((IssuedCode) result);
    assertThat(created.getCreatedAt()).isEqualTo(afterLock);
    assertThat(created.getExpiresAt()).isEqualTo(afterLock.plus(Duration.ofMinutes(5)));
    // 전날 센 한도는 되돌아가고, 새 날짜에 한 번만 센다
    assertThat(smsTotalOn(oldDay)).isEqualTo(4);
    assertThat(smsTotalOn(newDay)).isEqualTo(1);
    assertThat(verifications.count()).isEqualTo(5);
  }

  @Test
  void 전날_전체_한도가_다_찬_채_기다리다_한국_날짜가_바뀌면_새_날짜로_받는다() throws Exception {
    LocalDate oldDay = LocalDate.of(2026, 11, 19);
    LocalDate newDay = LocalDate.of(2026, 11, 20);
    clock.set(Instant.parse("2026-11-19T14:59:59Z")); // 한국 23:59:59
    jdbc.update(
        "INSERT INTO state.rate_counter (counter_key, window_date, count) VALUES ('sms-total', ?, 40)",
        java.sql.Date.valueOf(oldDay));
    CountDownLatch release =
        holdLock(() ->
            jdbc.queryForList(
                "SELECT count FROM state.rate_counter"
                    + " WHERE counter_key = 'sms-total' AND window_date = ? FOR UPDATE",
                java.sql.Date.valueOf(oldDay)));

    Future<Object> waiting = pool.submit(() -> outcome(() -> service.issue(PHONE, SIGNUP)));
    awaitLockWaiter();
    Instant afterLock = Instant.parse("2026-11-19T15:00:30Z"); // 한국 다음 날 00:00:30
    clock.set(afterLock);
    release.countDown();

    Object result = waiting.get(10, TimeUnit.SECONDS);
    assertThat(result).isInstanceOf(IssuedCode.class);
    assertThat(row((IssuedCode) result).getCreatedAt()).isEqualTo(afterLock);
    assertThat(smsTotalOn(oldDay)).isEqualTo(40);
    assertThat(smsTotalOn(newDay)).isEqualTo(1);
  }

  @Test
  void 바깥_트랜잭션_안에서_발급하면_코드_실수다() {
    assertThatThrownBy(() -> inTransaction(() -> service.issue(PHONE, SIGNUP)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("트랜잭션 밖");
    assertThat(verifications.count()).isZero();
  }

  @Test
  void 이전_번호_행_잠금을_기다리면_새_행과_이전_행의_시각을_잠금_뒤_한_시각으로_정한다() throws Exception {
    IssuedCode first = service.issue(PHONE, SIGNUP);
    clock.advance(RESEND);
    // 이전 번호를 확인하는 요청이 그 행을 잡고 있다
    CountDownLatch release = holdLock(() -> lockRow(first.verificationId()));

    Future<Object> waiting = pool.submit(() -> outcome(() -> service.issue(PHONE, SIGNUP)));
    awaitLockWaiter();
    Instant afterLock = START.plus(Duration.ofMinutes(2));
    clock.set(afterLock);
    release.countDown();

    Object result = waiting.get(10, TimeUnit.SECONDS);
    assertThat(result).isInstanceOf(IssuedCode.class);
    assertThat(row((IssuedCode) result).getCreatedAt()).isEqualTo(afterLock);
    assertThat(row((IssuedCode) result).getExpiresAt())
        .isEqualTo(afterLock.plus(Duration.ofMinutes(5)));
    assertThat(row(first).getExpiresAt()).isEqualTo(afterLock);
  }

  // 도우미

  /**
   * 다른 요청이 DB 잠금을 실제로 기다리고 있을 때까지 기다린다({@code pg_locks}에 받지 못한 잠금이 생김). 잠을 자는 시간으로 가정하지 않고, 대기에 들어간
   * 것을 확인한 뒤 시계를 옮긴다.
   */
  private void awaitLockWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class)
        == 0) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("요청이 잠금 대기에 들어가지 않았다");
      }
      Thread.sleep(10);
    }
  }

  /** 다른 트랜잭션에서 잠금을 잡고 둔다. 돌려준 래치를 내리면 커밋해 잠금을 푼다. */
  private CountDownLatch holdLock(Runnable lock) {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.submit(() ->
        transaction.executeWithoutResult(status -> {
          lock.run();
          locked.countDown();
          await(release);
        }));
    await(locked);
    return release;
  }

  private void lockRow(UUID id) {
    jdbc.queryForList("SELECT id FROM state.phone_verification WHERE id = ? FOR UPDATE", id);
  }

  private int smsTotalOn(LocalDate day) {
    List<Integer> counts =
        jdbc.queryForList(
            "SELECT count FROM state.rate_counter WHERE counter_key = 'sms-total' AND window_date = ?",
            Integer.class,
            java.sql.Date.valueOf(day));
    return counts.isEmpty() ? 0 : counts.get(0);
  }

  private String ticket(VerificationPurpose purpose) {
    IssuedCode code = service.issue(PHONE, purpose);
    return service.verify(code.verificationId(), code.code()).ticket();
  }

  private PhoneVerification row(IssuedCode code) {
    return verifications.findById(code.verificationId()).orElseThrow();
  }

  /** 받은 번호들과 다른 6자리. */
  private static String wrong(IssuedCode... codes) {
    for (int candidate = 0; ; candidate++) {
      String value = "%06d".formatted(candidate);
      boolean used = false;
      for (IssuedCode code : codes) {
        used |= code.code().equals(value);
      }
      if (!used) {
        return value;
      }
    }
  }

  private <T> T inTransaction(Callable<T> work) {
    return transaction.execute(status -> {
      try {
        return work.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  private int smsTotalToday() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count FROM state.rate_counter WHERE counter_key = 'sms-total' AND window_date = ?",
            Integer.class,
            java.sql.Date.valueOf(START.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()));
    return count == null ? 0 : count;
  }

  private void fillSmsTotal(int count) {
    jdbc.update(
        "INSERT INTO state.rate_counter (counter_key, window_date, count) VALUES ('sms-total', ?, ?)",
        java.sql.Date.valueOf(START.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()),
        count);
  }

  private static void assertCode(ThrowingCallable call, ErrorCode expected) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(expected);
  }

  /** 두 일을 거의 동시에 시작하고, 값이나 던진 예외를 돌려준다. */
  private List<Object> together(Callable<Object> a, Callable<Object> b) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (Callable<Object> work : List.of(a, b)) {
      futures.add(
          pool.submit(() -> {
            await(start);
            return outcome(work);
          }));
    }
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(10, TimeUnit.SECONDS));
    }
    return results;
  }

  private static Object outcome(Callable<?> work) {
    try {
      return work.call();
    } catch (Exception e) {
      return e;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("기다리다 시간이 지났다");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
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
