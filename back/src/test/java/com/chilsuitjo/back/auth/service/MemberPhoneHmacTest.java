package com.chilsuitjo.back.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.service.MemberPhoneHmacService;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import com.chilsuitjo.back.notification.service.VerifiedPhoneOwners;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 번호 HMAC 채우기와 번호 잠금 회원 확인(#462, #83 Q2). 잠금과 커밋 순서를 보려고 테스트 트랜잭션 없이 실제 PostgreSQL에서 돌리고, 만든 회원은
 * 끝나면 지운다. 예약 채우기는 테스트 설정(config/application.yml)이 끄고 직접 부른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MemberPhoneHmacTest {

  @Autowired private MemberPhoneHmacFill fill;
  @Autowired private MemberPhoneHmacService memberPhoneHmacs;
  @Autowired private MemberRepository members;
  @Autowired private VerifiedPhoneOwners phoneLock;
  @Autowired private VerificationKeys keys;
  @Autowired private PhoneHmacs phoneHmacs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);
  private final List<UUID> created = new ArrayList<>();

  @BeforeEach
  void fillLeftovers() {
    // 다른 테스트가 커밋한 채로 둔 빈 행이 있으면 회원 확인이 예외를 낸다. 먼저 채워 둔다
    fill.fillAll();
  }

  @AfterEach
  void cleanUp() {
    pool.shutdownNow();
    created.forEach(id -> jdbc.update("DELETE FROM state.member WHERE id = ?", id));
  }

  // 채우기

  @Test
  void 채우기_전에_읽은_엔티티가_다른_칸을_고쳐도_채운_값은_남는다() throws Exception {
    UUID id = insert("01033330013", null);
    CountDownLatch loaded = new CountDownLatch(1);
    CountDownLatch filled = new CountDownLatch(1);
    // 채우기 전에 회원을 읽어 둔 요청(예: 문자 설정 바꾸기)이 채우기가 커밋된 뒤 저장한다
    Future<?> request =
        pool.submit(() ->
            transaction.executeWithoutResult(status -> {
              Member member = members.findById(id).orElseThrow();
              loaded.countDown();
              await(filled);
              member.changeSmsEnabled(false);
            }));
    await(loaded);
    fill.fillAll();
    filled.countDown();
    request.get(10, TimeUnit.SECONDS);

    assertThat(phoneHmac(id)).isEqualTo(phoneHmacs.phoneHmac("01033330013"));
    assertThat(
            jdbc.queryForObject(
                "SELECT sms_enabled FROM state.member WHERE id = ?", Boolean.class, id))
        .isFalse();
  }

  @Test
  void 빈_행은_번호의_HMAC으로_채우고_다시_돌려도_그대로다() {
    UUID unfilled = insert("01033330001", null);
    UUID filled = insert("01033330002", phoneHmacs.phoneHmac("01033330002"));
    UUID noPhone = insert(null, null);

    assertThat(fill.fillAll()).isGreaterThanOrEqualTo(1);

    assertThat(phoneHmac(unfilled)).isEqualTo(phoneHmacs.phoneHmac("01033330001"));
    assertThat(phoneHmac(filled)).isEqualTo(phoneHmacs.phoneHmac("01033330002"));
    assertThat(phoneHmac(noPhone)).isNull();
    assertThat(fill.fillAll()).isZero();
    assertThat(phoneHmac(unfilled)).isEqualTo(phoneHmacs.phoneHmac("01033330001"));
  }

  @Test
  void 읽은_뒤_번호가_바뀐_행은_채우지_않는다() {
    UUID id = insert("01033330003", null);
    jdbc.update("UPDATE state.member SET phone = '01033330004' WHERE id = ?", id);

    // 채우기가 옛 번호를 읽어 둔 상태
    boolean filled = memberPhoneHmacs.fill(id, "01033330003", phoneHmacs.phoneHmac("01033330003"));

    assertThat(filled).isFalse();
    assertThat(phoneHmac(id)).isNull();
  }

  @Test
  void 한_배치보다_많은_빈_행도_모두_채운다() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < MemberPhoneHmacFill.BATCH_SIZE + 5; i++) {
      ids.add(insert(String.format("0104444%04d", i), null));
    }

    fill.fillAll();

    assertThat(ids).allSatisfy(id -> assertThat(phoneHmac(id)).isNotNull());
  }

  @Test
  void 계산할_수_없는_번호는_건너뛰고_나머지는_채운다() {
    UUID bad = insert("12345", null);
    UUID good = insert("01033330005", null);

    fill.fillAll();

    assertThat(phoneHmac(bad)).isNull();
    assertThat(phoneHmac(good)).isEqualTo(phoneHmacs.phoneHmac("01033330005"));
    jdbc.update("DELETE FROM state.member WHERE id = ?", bad);
  }

  // 번호 잠금 회원 확인

  @Test
  void 인증한_활동_회원의_번호면_true_아니면_false() {
    String hmac = phoneHmacs.phoneHmac("01033330006");
    insert("01033330006", hmac);

    assertThat(inTransaction(() -> phoneLock.lockAndCheckVerifiedMember(hmac))).isTrue();
    assertThat(
            inTransaction(
                () -> phoneLock.lockAndCheckVerifiedMember(phoneHmacs.phoneHmac("01033330007"))))
        .isFalse();
  }

  @Test
  void 번호를_푼_회원의_옛_번호는_회원이_아니다() {
    String hmac = phoneHmacs.phoneHmac("01033330008");
    UUID id = insert("01033330008", hmac);
    jdbc.update(
        "UPDATE state.member SET phone = NULL, phone_hmac = NULL, phone_verified_at = NULL WHERE id = ?",
        id);

    assertThat(inTransaction(() -> phoneLock.lockAndCheckVerifiedMember(hmac))).isFalse();
  }

  @Test
  void 채우지_않은_행이_있으면_비회원으로_답하지_않고_예외를_낸다() {
    UUID unfilled = insert("01033330009", null);

    assertThatThrownBy(() ->
            inTransaction(
                () -> phoneLock.lockAndCheckVerifiedMember(phoneHmacs.phoneHmac("01033330009"))))
        .isInstanceOf(IllegalStateException.class);

    fill.fillAll();
    assertThat(
            inTransaction(
                () -> phoneLock.lockAndCheckVerifiedMember(phoneHmacs.phoneHmac("01033330009"))))
        .isTrue();
    assertThat(unfilled).isNotNull();
  }

  @Test
  void 트랜잭션_밖이거나_HMAC_형식이_틀리면_부르지_않는다() {
    String hmac = phoneHmacs.phoneHmac("01033330010");

    assertThatThrownBy(() -> phoneLock.lockAndCheckVerifiedMember(hmac))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(
            () -> inTransaction(() -> phoneLock.lockAndCheckVerifiedMember("01033330010")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 같은_번호의_가입이_잠금을_잡고_있으면_기다렸다가_커밋된_회원을_본다() throws Exception {
    String phone = "01033330011";
    String hmac = phoneHmacs.phoneHmac(phone);
    UUID signedUp = UUID.randomUUID();
    created.add(signedUp);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // 가입(SignupService)처럼 번호 잠금 → 회원 저장, 커밋은 래치를 내린 뒤
    pool.submit(() ->
        transaction.executeWithoutResult(status -> {
          jdbc.queryForObject(
              "SELECT 1 FROM pg_advisory_xact_lock(?)", Integer.class, keys.phoneLockKey(phone));
          insertRow(signedUp, phone, hmac);
          locked.countDown();
          await(release);
        }));
    await(locked);

    Future<Boolean> check =
        pool.submit(() -> inTransaction(() -> phoneLock.lockAndCheckVerifiedMember(hmac)));
    awaitLockWaiter();
    assertThat(check.isDone()).isFalse();
    release.countDown();

    assertThat(check.get(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void 확인이_먼저_잠금을_잡으면_가입은_확인이_끝날_때까지_기다린다() throws Exception {
    String phone = "01033330012";
    String hmac = phoneHmacs.phoneHmac(phone);
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<Boolean> check =
        pool.submit(() ->
            transaction.execute(status -> {
              boolean member = phoneLock.lockAndCheckVerifiedMember(hmac);
              checked.countDown();
              await(release);
              return member;
            }));
    await(checked);

    UUID signedUp = UUID.randomUUID();
    created.add(signedUp);
    Future<?> signup =
        pool.submit(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.queryForObject(
                  "SELECT 1 FROM pg_advisory_xact_lock(?)",
                  Integer.class,
                  keys.phoneLockKey(phone));
              insertRow(signedUp, phone, hmac);
            }));
    awaitLockWaiter();
    assertThat(signup.isDone()).isFalse();
    release.countDown();

    assertThat(check.get(10, TimeUnit.SECONDS)).isFalse();
    signup.get(10, TimeUnit.SECONDS);
    assertThat(phoneHmac(signedUp)).isEqualTo(hmac);
  }

  private <T> T inTransaction(java.util.function.Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private UUID insert(String phone, String phoneHmac) {
    UUID id = UUID.randomUUID();
    created.add(id);
    insertRow(id, phone, phoneHmac);
    return id;
  }

  private void insertRow(UUID id, String phone, String phoneHmac) {
    jdbc.update(
        """
        INSERT INTO state.member (id, email, password_hash, display_name, phone, phone_hmac,
          phone_verified_at, sms_enabled, status, created_at, version)
        VALUES (?, ?, 'hash', '정서윤', ?, ?, CURRENT_TIMESTAMP, true, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        id + "@example.com",
        phone,
        phoneHmac);
  }

  private String phoneHmac(UUID id) {
    return jdbc.queryForObject(
        "SELECT phone_hmac FROM state.member WHERE id = ?", String.class, id);
  }

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
