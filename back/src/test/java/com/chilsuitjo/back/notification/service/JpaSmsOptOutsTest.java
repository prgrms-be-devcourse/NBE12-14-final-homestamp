package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수신 거부 조회(#163 1-3)·등록·해제(#370 A-1~A-3)를 실제 PostgreSQL과 실제 번호 HMAC 구현으로 본다. 번호는 임의라 다른 테스트와 겹치지
 * 않고, 넣은 거부 행과 아웃박스 줄은 지운다(삭제 감사는 지울 수 없는 B라 번호 HMAC으로만 골라 본다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class JpaSmsOptOutsTest {

  @Autowired private SmsOptOuts optOuts;
  @Autowired private PhoneHmacs phoneHmacs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private static final Instant AT = Instant.parse("2026-11-28T03:00:00Z");

  private final List<String> inserted = new ArrayList<>();
  private final List<Held<?>> held = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    // 단언이 중간에 실패해도 잡고 있는 트랜잭션·행 잠금을 먼저 풀어 정리 DELETE가 막히지 않게 한다
    for (Held<?> h : held) {
      h.rollback();
    }
    inserted.forEach(hmac -> {
      jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = ?", hmac);
      jdbc.update(
          "DELETE FROM state.outbox WHERE kind = 'AUDIT_WRITE' AND payload->>'deletionLogId' IN"
              + " (SELECT id::text FROM log.deletion_log WHERE phone_hmac = ?)",
          hmac);
    });
  }

  @Test
  void 운영_빈은_DB를_읽는_구현_하나다() {
    assertThat(AopUtils.getTargetClass(optOuts)).isEqualTo(JpaSmsOptOuts.class);
  }

  @Test
  void 거부_행이_있으면_true이고_형식만_다른_번호도_같다() {
    String phone = randomPhone();
    optOut(phone);

    assertThat(optOuts.isOptedOut(phone)).isTrue();
    assertThat(
            optOuts.isOptedOut(
                phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7)))
        .isTrue();
  }

  @Test
  void 행이_없으면_false다() {
    assertThat(optOuts.isOptedOut(randomPhone())).isFalse();
  }

  @Test
  void 다른_번호의_거부_행으로_true가_되지_않는다() {
    String phone = randomPhone();
    optOut(phone);
    String other =
        phone.substring(0, 10) + (phone.charAt(10) == '9' ? '0' : (char) (phone.charAt(10) + 1));

    assertThat(optOuts.isOptedOut(other)).isFalse();
  }

  @Test
  void 정규화할_수_없는_번호는_false가_아니라_VALIDATION_FAILED다() {
    assertThatThrownBy(() -> optOuts.isOptedOut("02-123-4567"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
        .hasMessage(ErrorCode.VALIDATION_FAILED.name());
  }

  // ---- 등록 ----

  @Test
  void 등록은_행을_넣고_같은_번호를_다시_넣으면_false이며_행은_하나다() {
    String phone = randomPhone();
    track(phone);

    assertThat(inTx(() -> optOuts.optOut(phone, AT))).isTrue();
    assertThat(inTx(() -> optOuts.optOut(dashed(phone), AT.plusSeconds(60)))).isFalse();

    assertThat(rows(phone)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM state.sms_opt_out WHERE phone_hmac = ?",
                Timestamp.class,
                phoneHmacs.phoneHmac(phone)))
        .isEqualTo(Timestamp.from(AT));
    assertThat(optOuts.isOptedOut(phone)).isTrue();
  }

  @Test
  void 등록과_해제는_트랜잭션_밖에서_부르면_아무것도_하지_않고_거절된다() {
    String phone = randomPhone();
    track(phone);

    assertThatThrownBy(() -> optOuts.optOut(phone, AT))
        .isInstanceOf(IllegalTransactionStateException.class);
    optOut(phone);
    assertThatThrownBy(() -> optOuts.releaseForVerifiedOwner(phone, AT))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isZero();
  }

  @Test
  void 정규화할_수_없는_번호는_등록도_해제도_VALIDATION_FAILED다() {
    assertThatThrownBy(() -> inTx(() -> optOuts.optOut("02-123-4567", AT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> inTx(() -> optOuts.releaseForVerifiedOwner("02-123-4567", AT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  // ---- 해제와 감사 ----

  @Test
  void 행이_있으면_해제는_true이고_행을_지우며_감사와_AUDIT_WRITE를_하나씩_남긴다() {
    String phone = randomPhone();
    optOut(phone);
    String hmac = phoneHmacs.phoneHmac(phone);

    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(dashed(phone), AT))).isTrue();

    assertThat(rows(phone)).isZero();
    assertThat(optOuts.isOptedOut(phone)).isFalse();
    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM log.deletion_log WHERE phone_hmac = ?", hmac);
    assertThat(audit)
        .containsEntry("target_kind", "SMS_OPT_OUT")
        .containsEntry("reason", "OWNER_VERIFIED")
        .containsEntry("created_at", Timestamp.from(AT));
    assertThat(audit.get("target_id")).isNull();
    assertThat(audit.get("chain_room_id")).isNull();
    UUID auditId = (UUID) audit.get("id");
    // 탈퇴 함수(record.purge_member_acknowledgements)와 같은 모양
    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT kind, status, attempts, payload::text AS payload, next_attempt_at, created_at"
                + " FROM state.outbox WHERE dedupe_key = ?",
            "audit:" + auditId);
    assertThat(outbox)
        .containsEntry("kind", "AUDIT_WRITE")
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("payload", "{\"deletionLogId\": \"" + auditId + "\"}")
        .containsEntry("next_attempt_at", Timestamp.from(AT))
        .containsEntry("created_at", Timestamp.from(AT));
  }

  @Test
  void 행이_없으면_해제는_false이고_감사도_아웃박스도_없다() {
    String phone = randomPhone();
    track(phone);

    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT))).isFalse();

    assertThat(audits(phone)).isZero();
    assertThat(auditOutboxes(phone)).isZero();
  }

  @Test
  void 이미_해제한_번호를_다시_해제하면_false이고_감사는_하나뿐이다() {
    String phone = randomPhone();
    optOut(phone);

    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT))).isTrue();
    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(60)))).isFalse();

    assertThat(audits(phone)).isEqualTo(1);
    assertThat(auditOutboxes(phone)).isEqualTo(1);
  }

  @Test
  void 해제한_뒤_부르는_쪽_트랜잭션이_실패하면_행_감사_아웃박스가_모두_되돌아간다() {
    String phone = randomPhone();
    optOut(phone);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    assertThatThrownBy(() ->
            tx.executeWithoutResult(status -> {
              assertThat(optOuts.releaseForVerifiedOwner(phone, AT)).isTrue();
              throw new IllegalStateException("가입 실패");
            }))
        .hasMessage("가입 실패");

    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isZero();
    assertThat(auditOutboxes(phone)).isZero();
  }

  @Test
  void 해제_뒤_다시_등록하면_거부가_다시_생기고_앞선_감사는_그대로다() {
    String phone = randomPhone();
    optOut(phone);
    inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT));

    assertThat(inTx(() -> optOuts.optOut(phone, AT.plusSeconds(60)))).isTrue();

    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isEqualTo(1);
  }

  // ---- 겹칠 때(#370 A-3, 번호 잠금 없음). 결과는 커밋 순서가 아니라 해제의 삭제 문장이 도는 순간에 정해진다 ----

  @Test
  void 행이_없을_때_커밋_전_등록과_해제가_겹치면_해제는_보지_못해_false이고_늦게_커밋해도_거부가_남는다() throws Exception {
    String phone = randomPhone();
    track(phone);
    Held<Boolean> register = hold(() -> optOuts.optOut(phone, AT));
    assertThat(register.value()).isTrue();

    // 등록이 커밋되기 전에 해제의 삭제 문장이 돈다: 기다리지 않고 0
    Held<Boolean> release = hold(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(1)));
    assertThat(release.value()).isFalse();
    // 커밋은 등록 → 해제 순서지만 결과는 "해제 → 등록"과 같다
    register.commit();
    release.commit();

    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isZero();
    assertThat(auditOutboxes(phone)).isZero();
  }

  @Test
  void 등록이_커밋된_뒤_해제의_삭제가_돌면_해제는_true이고_감사가_하나다() throws Exception {
    String phone = randomPhone();
    track(phone);
    Held<Boolean> register = hold(() -> optOuts.optOut(phone, AT));
    register.commit();

    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(1)))).isTrue();

    assertThat(rows(phone)).isZero();
    assertThat(audits(phone)).isEqualTo(1);
  }

  @Test
  void 행이_있을_때_해제가_먼저_지우면_등록은_그_커밋을_기다렸다가_다시_넣고_감사는_하나다() throws Exception {
    String phone = randomPhone();
    optOut(phone);
    Held<Boolean> release = hold(() -> optOuts.releaseForVerifiedOwner(phone, AT));
    assertThat(release.value()).isTrue();

    CompletableFuture<Boolean> register =
        CompletableFuture.supplyAsync(() -> inTx(() -> optOuts.optOut(phone, AT.plusSeconds(1))));
    awaitOptOutLockWaiter();
    assertThat(register).isNotDone();
    release.commit();

    assertThat(register.get(10, TimeUnit.SECONDS)).isTrue();
    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isEqualTo(1);
  }

  @Test
  void 행이_있을_때_해제가_되돌아가면_기다린_등록은_이미_있는_행이라_false다() throws Exception {
    String phone = randomPhone();
    optOut(phone);
    Held<Boolean> release = hold(() -> optOuts.releaseForVerifiedOwner(phone, AT));
    assertThat(release.value()).isTrue();

    CompletableFuture<Boolean> register =
        CompletableFuture.supplyAsync(() -> inTx(() -> optOuts.optOut(phone, AT.plusSeconds(1))));
    awaitOptOutLockWaiter();
    release.rollback();

    assertThat(register.get(10, TimeUnit.SECONDS)).isFalse();
    assertThat(rows(phone)).isEqualTo(1);
    assertThat(audits(phone)).isZero();
  }

  @Test
  void 행이_있을_때_등록이_먼저면_아무것도_하지_않고_뒤의_해제가_지운다() throws Exception {
    String phone = randomPhone();
    optOut(phone);
    Held<Boolean> register = hold(() -> optOuts.optOut(phone, AT));
    // 이미 커밋된 행이라 기다리지도 잠그지도 않는다
    assertThat(register.value()).isFalse();

    assertThat(inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(1)))).isTrue();
    register.commit();

    assertThat(rows(phone)).isZero();
    assertThat(audits(phone)).isEqualTo(1);
  }

  @Test
  void 두_해제가_겹치면_뒤의_해제는_앞의_커밋을_기다렸다가_false이고_감사는_하나다() throws Exception {
    String phone = randomPhone();
    optOut(phone);
    Held<Boolean> first = hold(() -> optOuts.releaseForVerifiedOwner(phone, AT));
    assertThat(first.value()).isTrue();

    CompletableFuture<Boolean> second =
        CompletableFuture.supplyAsync(
            () -> inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(1))));
    awaitOptOutLockWaiter();
    assertThat(second).isNotDone();
    first.commit();

    assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
    assertThat(rows(phone)).isZero();
    assertThat(audits(phone)).isEqualTo(1);
    assertThat(auditOutboxes(phone)).isEqualTo(1);
  }

  @Test
  void 앞의_해제가_되돌아가면_기다린_해제가_지우고_감사는_그것_하나다() throws Exception {
    String phone = randomPhone();
    optOut(phone);
    Held<Boolean> first = hold(() -> optOuts.releaseForVerifiedOwner(phone, AT));
    assertThat(first.value()).isTrue();

    CompletableFuture<Boolean> second =
        CompletableFuture.supplyAsync(
            () -> inTx(() -> optOuts.releaseForVerifiedOwner(phone, AT.plusSeconds(1))));
    awaitOptOutLockWaiter();
    first.rollback();

    assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
    assertThat(rows(phone)).isZero();
    assertThat(audits(phone)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM log.deletion_log WHERE phone_hmac = ?",
                Timestamp.class,
                phoneHmacs.phoneHmac(phone)))
        .isEqualTo(Timestamp.from(AT.plusSeconds(1)));
  }

  @Test
  void 두_등록이_겹치면_뒤의_등록은_앞의_커밋을_기다렸다가_false이고_행은_하나다() throws Exception {
    String phone = randomPhone();
    track(phone);
    Held<Boolean> first = hold(() -> optOuts.optOut(phone, AT));
    assertThat(first.value()).isTrue();

    CompletableFuture<Boolean> second =
        CompletableFuture.supplyAsync(() -> inTx(() -> optOuts.optOut(phone, AT.plusSeconds(1))));
    awaitOptOutLockWaiter();
    first.commit();

    assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
    assertThat(rows(phone)).isEqualTo(1);
  }

  // ---- 도우미 ----

  /** 다른 스레드에서 트랜잭션을 열어 일을 하고, {@link #commit}·{@link #rollback} 전까지 끝내지 않는다. */
  private final class Held<T> {
    private final CompletableFuture<T> value = new CompletableFuture<>();
    private final CompletableFuture<Boolean> finish = new CompletableFuture<>();
    private final CompletableFuture<Void> done;

    private Held(Supplier<T> work) {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      done =
          CompletableFuture.runAsync(() ->
              tx.executeWithoutResult(status -> {
                try {
                  value.complete(work.get());
                } catch (RuntimeException e) {
                  value.completeExceptionally(e);
                  throw e;
                }
                if (!finish.join()) {
                  status.setRollbackOnly();
                }
              }));
    }

    T value() throws Exception {
      return value.get(10, TimeUnit.SECONDS);
    }

    void commit() throws Exception {
      finish.complete(true);
      done.get(10, TimeUnit.SECONDS);
    }

    void rollback() throws Exception {
      finish.complete(false);
      done.get(10, TimeUnit.SECONDS);
    }
  }

  private <T> Held<T> hold(Supplier<T> work) {
    Held<T> h = new Held<>(work);
    held.add(h);
    return h;
  }

  private <T> T inTx(Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  /** {@code sms_opt_out} 행을 기다리는 연결이 생길 때까지. */
  private void awaitOptOutLockWaiter() throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                  + " AND query ILIKE '%sms_opt_out%' AND pid <> pg_backend_pid()",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("수신 거부 행을 기다리는 연결이 없음");
  }

  private int rows(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.sms_opt_out WHERE phone_hmac = ?",
        Integer.class,
        phoneHmacs.phoneHmac(phone));
  }

  private int audits(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM log.deletion_log WHERE phone_hmac = ?",
        Integer.class,
        phoneHmacs.phoneHmac(phone));
  }

  private int auditOutboxes(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.outbox WHERE kind = 'AUDIT_WRITE' AND payload->>'deletionLogId'"
            + " IN (SELECT id::text FROM log.deletion_log WHERE phone_hmac = ?)",
        Integer.class,
        phoneHmacs.phoneHmac(phone));
  }

  private void track(String phone) {
    inserted.add(phoneHmacs.phoneHmac(phone));
  }

  private static String dashed(String phone) {
    return phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
  }

  private void optOut(String phone) {
    String hmac = phoneHmacs.phoneHmac(phone);
    jdbc.update("INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES (?, now())", hmac);
    inserted.add(hmac);
  }

  private static String randomPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
