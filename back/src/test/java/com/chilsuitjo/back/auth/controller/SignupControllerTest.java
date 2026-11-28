package com.chilsuitjo.back.auth.controller;

import static com.chilsuitjo.back.auth.rule.VerificationPurpose.RESET;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.SIGNUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.auth.service.PhoneVerificationService;
import com.chilsuitjo.back.auth.service.SignupService;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.error.GlobalExceptionHandler;
import com.chilsuitjo.back.member.dto.SignupRequest;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import com.chilsuitjo.back.notification.service.SmsOptOuts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 4-1 {@code POST /api/auth/signup}과 ACC-1·ACC-3a 수용 기준. 보안 설정까지 띄워 로그인 없이 부른다. 인증번호는 서비스로
 * 만든다(보내기 API는 D 계약 뒤).
 *
 * <p>확인 기록은 지울 수 없는 표(B)라 테스트마다 지우지 않고, 이메일·번호를 테스트마다 새로 만든다.
 */
@Import({TestcontainersConfiguration.class, SignupControllerTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SignupControllerTest {

  private static final Instant START = Instant.parse("2026-11-19T01:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private PhoneVerificationService verifications;
  @Autowired private SignupService signups;
  @MockitoSpyBean private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;
  @MockitoSpyBean private SmsOptOuts smsOptOuts;
  @Autowired private PhoneHmacs phoneHmacs;
  @Autowired private TransactionTemplate transaction;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);
  private final List<String> optedOutHmacs = new ArrayList<>();

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

  /** 수신 거부 행과 해제가 만든 AUDIT_WRITE 줄을 지운다. 감사({@code deletion_log})는 지울 수 없는 표라 번호를 테스트마다 새로 만든다. */
  @AfterEach
  void cleanOptOuts() {
    optedOutHmacs.forEach(hmac -> {
      jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = ?", hmac);
      jdbc.update(
          "DELETE FROM state.outbox WHERE kind = 'AUDIT_WRITE' AND payload->>'deletionLogId' IN"
              + " (SELECT id::text FROM log.deletion_log WHERE phone_hmac = ?)",
          hmac);
    });
    optedOutHmacs.clear();
  }

  // ACC-1b 가입하면 회원과 확인 기록 세 줄이 남는다

  @Test
  void 가입하면_201과_회원_ID만_돌려주고_회원과_확인_기록_세_줄이_남는다() throws Exception {
    String phone = newPhone();
    String email = newEmail();
    Map<String, Object> body = body(email, ticket(phone, SIGNUP));

    String response =
        signUp(body)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID id = UUID.fromString(response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    Map<String, Object> member =
        jdbc.queryForMap(
            "SELECT email, display_name, phone, password_hash, status, sms_enabled,"
                + " phone_verified_at FROM state.member WHERE id = ?",
            id);
    assertThat(member)
        .containsEntry("email", email)
        .containsEntry("display_name", "정서윤")
        .containsEntry("phone", phone)
        .containsEntry("status", "ACTIVE")
        .containsEntry("sms_enabled", true);
    assertThat(member.get("phone_verified_at")).isNotNull();
    String hash = (String) member.get("password_hash");
    assertThat(hash).startsWith("{bcrypt}");
    assertThat(passwordEncoder.matches("abc12345", hash)).isTrue();

    assertThat(acknowledgements(id))
        .containsOnly(
            Map.entry("AGE_14_CONFIRMED", "v1"),
            Map.entry("TERMS_AGREED", "v1"),
            Map.entry("PRIVACY_NOTICE_ACK", "v1"));
    assertThat(response).doesNotContain(phone).doesNotContain("abc12345").doesNotContain(hash);
  }

  // CONS-4 가입 문구 판은 지금 판(terms.version.*, 기본 v1)이어야 한다

  @Test
  void CONS_4_지금_판이_아닌_문구로_가입하면_VERSION_CONFLICT이고_회원과_인증_표가_남지_않는다() throws Exception {
    String phone = newPhone();
    String ticket = ticket(phone, SIGNUP);
    Map<String, Object> body = body(newEmail(), ticket);
    body.put("termsTextVersion", "2026-11-13");

    signUp(body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.name()));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.member WHERE phone = ?", Integer.class, phone))
        .isZero();
    // 인증 표는 쓰지 않았으므로 지금 판으로 다시 보내면 가입된다
    body.put("termsTextVersion", "v1");
    signUp(body).andExpect(status().isCreated());
  }

  // ACC-1a·CONS-1a 확인 항목

  @Test
  void ACC_1a_만_14세_확인이_없으면_AGE_CONFIRM_REQUIRED이고_인증_표는_남는다() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    Map<String, Object> body = body(newEmail(), ticket);
    body.put("ageConfirmed", false);

    expectProblem(signUp(body), ErrorCode.AGE_CONFIRM_REQUIRED);
    signUp(body(newEmail(), ticket)).andExpect(status().isCreated());
  }

  @Test
  void 약관이나_처리_안내를_확인하지_않으면_TERMS_REQUIRED() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    Map<String, Object> noTerms = body(newEmail(), ticket);
    noTerms.put("termsAgreed", false);
    Map<String, Object> noNotice = body(newEmail(), ticket);
    noNotice.remove("privacyNoticeAcknowledged");

    expectProblem(signUp(noTerms), ErrorCode.TERMS_REQUIRED);
    expectProblem(signUp(noNotice), ErrorCode.TERMS_REQUIRED);
  }

  // ACC-1c·1f 이미 쓰는 이메일, 실패하면 표는 남는다

  @Test
  void ACC_1c_1f_이미_쓰는_이메일이면_EMAIL_TAKEN이고_같은_표로_이메일만_바꿔_다시_가입한다() throws Exception {
    String taken = newEmail();
    signUp(body(taken, ticket(newPhone(), SIGNUP))).andExpect(status().isCreated());
    String ticket = ticket(newPhone(), SIGNUP);

    expectProblem(signUp(body(taken.toUpperCase(), ticket)), ErrorCode.EMAIL_TAKEN);
    signUp(body(newEmail(), ticket)).andExpect(status().isCreated());
  }

  // ACC-1d 비밀번호 규칙

  @Test
  void ACC_1d_숫자나_영문이_없거나_8자_미만이면_VALIDATION_FAILED() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    for (String password : List.of("abcdefgh", "1234567", "12345678")) {
      Map<String, Object> body = body(newEmail(), ticket);
      body.put("password", password);
      expectProblem(signUp(body), ErrorCode.VALIDATION_FAILED);
    }
    signUp(body(newEmail(), ticket)).andExpect(status().isCreated());
  }

  @Test
  void 비밀번호가_UTF_8_72바이트를_넘으면_해시하기_전에_VALIDATION_FAILED이고_72바이트는_가입된다() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    String at72 = "가".repeat(23) + "a12"; // 72바이트
    Map<String, Object> tooLong = body(newEmail(), ticket);
    tooLong.put("password", at72 + "3");

    expectProblem(signUp(tooLong), ErrorCode.VALIDATION_FAILED);

    Map<String, Object> atLimit = body(newEmail(), ticket);
    atLimit.put("password", at72);
    UUID id = signUpOk(atLimit);
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM state.member WHERE id = ?", String.class, id);
    assertThat(passwordEncoder.matches(at72, hash)).isTrue();
  }

  @Test
  void 인증_표가_없으면_VALIDATION_FAILED() throws Exception {
    Map<String, Object> body = body(newEmail(), null);
    expectProblem(signUp(body), ErrorCode.VALIDATION_FAILED);
  }

  // ACC-1f 인증 표는 용도가 맞는 작업이 성공할 때 한 번만 쓰인다

  @Test
  void ACC_1f_가입에_성공한_표를_다시_쓰면_CODE_EXPIRED() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    signUp(body(newEmail(), ticket)).andExpect(status().isCreated());

    expectProblem(signUp(body(newEmail(), ticket)), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void ACC_1f_비밀번호_재설정_표나_모양이_틀린_표로는_가입할_수_없다() throws Exception {
    expectProblem(signUp(body(newEmail(), ticket(newPhone(), RESET))), ErrorCode.CODE_EXPIRED);
    expectProblem(signUp(body(newEmail(), "not-a-ticket")), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 쓸_수_없는_표면_이미_쓰는_이메일이어도_EMAIL_TAKEN이_아니라_CODE_EXPIRED() throws Exception {
    String taken = newEmail();
    String used = ticket(newPhone(), SIGNUP);
    signUp(body(taken, used)).andExpect(status().isCreated());
    String reset = ticket(newPhone(), RESET);
    String expiring = ticket(newPhone(), SIGNUP);
    clock.advance(Duration.ofMinutes(15));

    for (String ticket : List.of("not-a-ticket", used, reset, expiring)) {
      expectProblem(signUp(body(taken, ticket)), ErrorCode.CODE_EXPIRED);
    }
  }

  @Test
  void 쓸_수_없는_표면_비밀번호를_해시하지_않는다() throws Exception {
    expectProblem(signUp(body(newEmail(), "not-a-ticket")), ErrorCode.CODE_EXPIRED);

    verify(passwordEncoder, never()).encode(any());
  }

  @Test
  void ACC_1f_같은_표로_두_가입이_동시에_와도_하나만_성공한다() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);
    List<Object> results =
        concurrently(
            () -> signups.signUp(request(newEmail(), ticket)),
            () -> signups.signUp(request(newEmail(), ticket)));

    assertThat(results).filteredOn(UUID.class::isInstance).hasSize(1);
    assertThat(results)
        .filteredOn(BusinessException.class::isInstance)
        .singleElement()
        .satisfies(
            e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
  }

  // ACC-1l 인증 표는 인증한 때부터 verify.ticket.ttl 동안 쓴다

  @Test
  void ACC_1l_번호가_만료되기_10초_전에_인증했어도_그때부터_15분_동안_가입할_수_있다() throws Exception {
    String phone = newPhone();
    IssuedCode code = verifications.issue(phone, SIGNUP);
    clock.advance(Duration.ofMinutes(5).minusSeconds(10));
    String ticket = verifications.verify(code.verificationId(), code.code()).ticket();

    clock.advance(Duration.ofMinutes(15).minusSeconds(1));
    signUp(body(newEmail(), ticket)).andExpect(status().isCreated());
  }

  @Test
  void ACC_1l_인증한_때부터_15분이_지나면_CODE_EXPIRED() throws Exception {
    String ticket = ticket(newPhone(), SIGNUP);

    clock.advance(Duration.ofMinutes(15));
    expectProblem(signUp(body(newEmail(), ticket)), ErrorCode.CODE_EXPIRED);
  }

  // ACC-3a 다른 계정이 같은 번호를 인증하면 이전 계정의 인증이 풀린다

  @Test
  void ACC_3a_같은_번호로_새로_가입하면_이전_계정의_번호가_비워진다() throws Exception {
    String phone = newPhone();
    UUID previous = signUpOk(body(newEmail(), ticket(phone, SIGNUP)));
    clock.advance(Duration.ofSeconds(61)); // 다시 받기 간격

    UUID current = signUpOk(body(newEmail(), ticket(phone, SIGNUP)));

    assertThat(phoneOf(previous)).isNull();
    assertThat(phoneVerifiedAtOf(previous)).isNull();
    assertThat(phoneOf(current)).isEqualTo(phone);
    assertThat(phoneVerifiedAtOf(current)).isNotNull();
  }

  @Test
  void ACC_3a_같은_번호의_표_두_개로_동시에_가입해도_번호는_한_계정에만_남는다() throws Exception {
    String phone = newPhone();
    String first = ticket(phone, SIGNUP);
    clock.advance(Duration.ofSeconds(61));
    String second = ticket(phone, SIGNUP);

    List<Object> results =
        concurrently(
            () -> signups.signUp(request(newEmail(), first)),
            () -> signups.signUp(request(newEmail(), second)));

    assertThat(results).allMatch(UUID.class::isInstance);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.member WHERE phone = ?", Integer.class, phone))
        .isOne();
  }

  // #105 비밀번호·인증 표·번호는 가입이 실패해도 로그에 남지 않는다. 기본 로그 수준에서 본다.
  // 처리하지 못한 예외(DB 제약 오류 등)의 Handler 로그는 #125가 맡는다

  @Test
  void 가입이_실패해도_비밀번호_인증_표_번호가_로그에_남지_않는다(CapturedOutput output) throws Exception {
    String phone = newPhone();
    String ticket = ticket(phone, SIGNUP);
    String taken = newEmail();
    signUpOk(body(taken, ticket(newPhone(), SIGNUP)));
    String weak = "logcheckonlyletters"; // 숫자가 없어 VALIDATION_FAILED
    String secret = "logcheck7secret"; // 규칙에 맞는 비밀번호
    int from = output.getAll().length();

    Map<String, Object> weakPassword = body(newEmail(), ticket);
    weakPassword.put("password", weak);
    expectProblem(signUp(weakPassword), ErrorCode.VALIDATION_FAILED);
    Map<String, Object> noTerms = body(newEmail(), ticket);
    noTerms.put("password", secret);
    noTerms.put("termsAgreed", false);
    expectProblem(signUp(noTerms), ErrorCode.TERMS_REQUIRED);
    Map<String, Object> emailTaken = body(taken, ticket);
    emailTaken.put("password", secret);
    expectProblem(signUp(emailTaken), ErrorCode.EMAIL_TAKEN);
    Map<String, Object> badTicket = body(newEmail(), ticket + "x");
    badTicket.put("password", secret);
    expectProblem(signUp(badTicket), ErrorCode.CODE_EXPIRED);

    // 로그가 이 출력으로 잡히는지 확인한다(잡히지 않으면 아래 검사는 늘 통과한다)
    String canary = "log-capture-canary-" + UUID.randomUUID();
    LoggerFactory.getLogger(GlobalExceptionHandler.class).warn(canary);
    assertThat(output.getAll().substring(from)).contains(canary);
    assertThat(output.getAll().substring(from))
        .doesNotContain(weak)
        .doesNotContain(secret)
        .doesNotContain(ticket)
        .doesNotContain(phone);
  }

  // NOTI-8c 그만 받았던 번호의 주인이 가입을 완료하면 수신 거부가 풀린다(#438)

  @Test
  void NOTI_8c_거부된_번호로_가입하면_거부가_풀리고_가입_시각으로_감사와_AUDIT_WRITE가_하나씩_남는다() throws Exception {
    String phone = newPhone();
    // 형식만 다른 번호(하이픈)로 거부했어도 같은 번호다
    optOut(hyphenated(phone));
    String ticket = ticket(phone, SIGNUP);
    clock.advance(Duration.ofSeconds(30)); // 인증과 가입 완료 시각을 나눈다

    UUID id = signUpOk(body(newEmail(), ticket));

    Instant completedAt = START.plusSeconds(30);
    assertThat(optedOutRows(phone)).isZero();
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT id, target_kind, target_id, reason, created_at FROM log.deletion_log"
                + " WHERE phone_hmac = ?",
            hmac(phone));
    assertThat(audit)
        .containsEntry("target_kind", "SMS_OPT_OUT")
        .containsEntry("reason", "OWNER_VERIFIED");
    assertThat(audit.get("target_id")).isNull();
    assertThat(((Timestamp) audit.get("created_at")).toInstant()).isEqualTo(completedAt);
    assertThat(createdAtOf(id)).isEqualTo(completedAt);
    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT status, created_at, next_attempt_at FROM state.outbox"
                + " WHERE kind = 'AUDIT_WRITE' AND dedupe_key = ?",
            "audit:" + audit.get("id"));
    assertThat(outbox).containsEntry("status", "PENDING");
    assertThat(((Timestamp) outbox.get("created_at")).toInstant()).isEqualTo(completedAt);
    assertThat(((Timestamp) outbox.get("next_attempt_at")).toInstant()).isEqualTo(completedAt);
    assertThat(auditWriteRows(phone)).isOne();
  }

  @Test
  void NOTI_8c_거부되지_않은_번호로_가입하면_감사를_남기지_않는다() throws Exception {
    String phone = newPhone();
    String ticket = ticket(phone, SIGNUP);
    clock.advance(Duration.ofSeconds(30));

    signUpOk(body(newEmail(), ticket));

    // 해제는 불렸지만(가입 완료 시각으로) 지울 행이 없어 아무것도 남지 않는다
    verify(spiedOptOuts()).releaseForVerifiedOwner(phone, START.plusSeconds(30));
    assertThat(auditRows(phone)).isZero();
    assertThat(auditWriteRows(phone)).isZero();
  }

  @Test
  void NOTI_8c_인증_코드_발급과_확인만으로는_거부가_풀리지_않는다() {
    String phone = newPhone();
    optOut(phone);

    ticket(phone, SIGNUP);

    verify(spiedOptOuts(), never()).releaseForVerifiedOwner(any(), any());
    assertThat(optedOutRows(phone)).isOne();
    assertThat(auditRows(phone)).isZero();
  }

  @Test
  void NOTI_8c_해제가_실패하면_가입_전체가_되돌아가고_같은_표로_다시_가입할_수_있다() throws Exception {
    String phone = newPhone();
    UUID previous = signUpOk(body(newEmail(), ticket(phone, SIGNUP)));
    clock.advance(Duration.ofSeconds(61)); // 다시 받기 간격
    optOut(phone);
    String ticket = ticket(phone, SIGNUP);
    String email = newEmail();
    // 실제 해제(행 삭제·감사·AUDIT_WRITE)를 끝낸 뒤 던져서, 아래 "그대로"가 실행 안 됨이 아니라 롤백임을 본다
    AtomicReference<Boolean> released = new AtomicReference<>();
    doAnswer(
            invocation -> {
              released.set((Boolean) invocation.callRealMethod());
              throw new IllegalStateException("해제 뒤 실패");
            })
        .when(spiedOptOuts())
        .releaseForVerifiedOwner(any(), any());

    assertThatThrownBy(() -> signups.signUp(request(email, ticket)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(released.get()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.member WHERE email = ?", Integer.class, email))
        .isZero();
    assertThat(phoneOf(previous)).isEqualTo(phone); // ACC-3a 번호 비우기도 되돌아감
    assertThat(phoneVerifiedAtOf(previous)).isNotNull();
    assertThat(optedOutRows(phone)).isOne();
    assertThat(auditRows(phone)).isZero();
    assertThat(auditWriteRows(phone)).isZero();

    // 인증 표도 쓰지 않은 채로 남아 같은 표로 다시 가입된다(ACC-1f)
    reset(spiedOptOuts());
    UUID current = signUpOk(body(email, ticket));
    assertThat(phoneOf(current)).isEqualTo(phone);
    assertThat(phoneOf(previous)).isNull();
    assertThat(optedOutRows(phone)).isZero();
    assertThat(auditRows(phone)).isOne();
    assertThat(auditWriteRows(phone)).isOne();
  }

  @Test
  void NOTI_8c_거부가_풀려도_옛_초대_자리와_무효가_된_링크는_되살아나지_않는다() throws Exception {
    String phone = newPhone();
    UUID issuer = signUpOk(body(newEmail(), ticket(newPhone(), SIGNUP)));
    optOut(phone);
    // 그만 받기로 번호만 지워진 초대 자리(NOTI-8a: INVITED, 번호 없음)와 그 자리의 무효 링크
    UUID clearedRoom = room();
    UUID clearedSeat = invitedSeat(clearedRoom, null);
    UUID oldLink = invalidatedLink(clearedRoom, clearedSeat, issuer);
    // 같은 번호가 아직 남은 다른 호실의 초대 자리: 가입이 번호로 자리를 찾아 연결·변경하지 않는다
    UUID numberedRoom = room();
    UUID numberedSeat = invitedSeat(numberedRoom, phone);

    signUpOk(body(newEmail(), ticket(phone, SIGNUP)));

    assertThat(optedOutRows(phone)).isZero();
    assertThat(seat(clearedSeat))
        .containsEntry("status", "INVITED")
        .containsEntry("invite_phone", null)
        .containsEntry("member_id", null)
        .containsEntry("version", 0L);
    assertThat(seat(numberedSeat))
        .containsEntry("status", "INVITED")
        .containsEntry("invite_phone", phone)
        .containsEntry("member_id", null)
        .containsEntry("version", 0L);
    Map<String, Object> link =
        jdbc.queryForMap(
            "SELECT invalidated_reason, invalidated_at, version FROM state.link WHERE id = ?",
            oldLink);
    assertThat(link).containsEntry("invalidated_reason", "REINVITED").containsEntry("version", 0L);
    assertThat(link.get("invalidated_at")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.link WHERE room_record_id IN (?, ?)",
                Integer.class,
                clearedRoom,
                numberedRoom))
        .isOne();
  }

  // 도움

  private void optOut(String phone) {
    transaction.executeWithoutResult(status -> smsOptOuts.optOut(phone, START));
    optedOutHmacs.add(hmac(phone));
    // 준비에서 부른 등록이 검사에 섞이지 않게 기록을 지운다
    clearInvocations(spiedOptOuts());
  }

  /** 스파이 자신. {@code @Transactional} 프록시를 거치면 준비·검사 호출도 MANDATORY 검사에 걸려서 그 안의 대상에 건다. */
  private SmsOptOuts spiedOptOuts() {
    return AopTestUtils.getUltimateTargetObject(smsOptOuts);
  }

  private String hmac(String phone) {
    return phoneHmacs.phoneHmac(phone);
  }

  private int optedOutRows(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.sms_opt_out WHERE phone_hmac = ?", Integer.class, hmac(phone));
  }

  private int auditRows(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM log.deletion_log WHERE phone_hmac = ?", Integer.class, hmac(phone));
  }

  /** 해제 감사에 딸린 AUDIT_WRITE 줄 수. 감사와 한 문장으로 들어가므로 감사 ID로 찾는다. */
  private int auditWriteRows(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.outbox WHERE kind = 'AUDIT_WRITE' AND payload->>'deletionLogId'"
            + " IN (SELECT id::text FROM log.deletion_log WHERE phone_hmac = ?)",
        Integer.class,
        hmac(phone));
  }

  /** 테스트 호실. DB가 지우기를 막아(purge owner) 남겨 두고 ID를 테스트마다 새로 만든다. */
  private UUID room() {
    UUID room = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    jdbc.update(
        """
        INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    return room;
  }

  private UUID invitedSeat(UUID room, String invitePhone) {
    UUID party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', ?, 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room,
        invitePhone);
    return party;
  }

  private UUID invalidatedLink(UUID room, UUID party, UUID issuer) {
    UUID link = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.link (id, room_record_id, kind, party_id, token_hash, issued_by_member_id,
                                invalidated_at, invalidated_reason, created_at, version)
        VALUES (?, ?, 'INVITE', ?, ?, ?, CURRENT_TIMESTAMP, 'REINVITED', CURRENT_TIMESTAMP, 0)
        """,
        link,
        room,
        party,
        "signup-test-" + link,
        issuer);
    return link;
  }

  private Map<String, Object> seat(UUID party) {
    return jdbc.queryForMap(
        "SELECT status, invite_phone, member_id, version FROM state.party WHERE id = ?", party);
  }

  private Instant createdAtOf(UUID memberId) {
    return jdbc.queryForObject(
            "SELECT created_at FROM state.member WHERE id = ?", Timestamp.class, memberId)
        .toInstant();
  }

  private static String hyphenated(String phone) {
    return phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);
  }

  private String ticket(String phone, VerificationPurpose purpose) {
    IssuedCode code = verifications.issue(phone, purpose);
    return verifications.verify(code.verificationId(), code.code()).ticket();
  }

  private static Map<String, Object> body(String email, String ticket) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", email);
    body.put("password", "abc12345");
    body.put("displayName", "정서윤");
    body.put("verificationTicket", ticket);
    body.put("ageConfirmed", true);
    body.put("ageTextVersion", "v1");
    body.put("termsAgreed", true);
    body.put("termsTextVersion", "v1");
    body.put("privacyNoticeAcknowledged", true);
    body.put("privacyNoticeTextVersion", "v1");
    return body;
  }

  private static SignupRequest request(String email, String ticket) {
    return new SignupRequest(email, "abc12345", "정서윤", ticket, true, "v1", true, "v1", true, "v1");
  }

  private ResultActions signUp(Map<String, Object> body) throws Exception {
    return mvc.perform(
        post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json(body)));
  }

  private UUID signUpOk(Map<String, Object> body) throws Exception {
    String response =
        signUp(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 값은 글자·참거짓·null뿐이고 글자에 따옴표·역슬래시가 없다. */
  private static String json(Map<String, Object> body) {
    return body.entrySet().stream()
        .map(e ->
            "\""
                + e.getKey()
                + "\":"
                + (e.getValue() instanceof String s ? "\"" + s + "\"" : e.getValue()))
        .collect(Collectors.joining(",", "{", "}"));
  }

  private Object phoneVerifiedAtOf(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT phone_verified_at FROM state.member WHERE id = ?", Object.class, memberId);
  }

  private String phoneOf(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT phone FROM state.member WHERE id = ?", String.class, memberId);
  }

  private Map<String, String> acknowledgements(UUID memberId) {
    Map<String, String> rows = new LinkedHashMap<>();
    jdbc.query(
        "SELECT kind, text_version FROM record.member_acknowledgement WHERE member_id = ?",
        rs -> {
          rows.put(rs.getString("kind"), rs.getString("text_version"));
        },
        memberId);
    return rows;
  }

  private static String newEmail() {
    return "signup-" + UUID.randomUUID() + "@example.com";
  }

  private static String newPhone() {
    return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }

  /** 두 일을 거의 동시에 시작하고, 결과 또는 던진 예외를 모은다. */
  @SafeVarargs
  private List<Object> concurrently(java.util.concurrent.Callable<Object>... works)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (var work : works) {
      futures.add(
          pool.submit(() -> {
            start.await();
            try {
              return work.call();
            } catch (RuntimeException e) {
              return e;
            }
          }));
    }
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(30, TimeUnit.SECONDS));
    }
    return results;
  }

  private static ResultActions expectProblem(ResultActions result, ErrorCode code)
      throws Exception {
    return result
        .andExpect(status().is(code.status().value()))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.detail").value(code.message()));
  }

  static class MutableClock extends Clock {

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
