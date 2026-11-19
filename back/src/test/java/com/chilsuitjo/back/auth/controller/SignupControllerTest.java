package com.chilsuitjo.back.auth.controller;

import static com.chilsuitjo.back.auth.rule.VerificationPurpose.RESET;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.SIGNUP;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.chilsuitjo.back.member.dto.SignupRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
class SignupControllerTest {

  private static final Instant START = Instant.parse("2026-11-19T01:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private PhoneVerificationService verifications;
  @Autowired private SignupService signups;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;

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

  // ACC-1b 가입하면 회원과 확인 기록 세 줄이 남는다

  @Test
  void 가입하면_201과_회원_ID만_돌려주고_회원과_확인_기록_세_줄이_남는다() throws Exception {
    String phone = newPhone();
    String email = newEmail();
    Map<String, Object> body = body(email, ticket(phone, SIGNUP));
    body.put("ageTextVersion", "2026-11-12");
    body.put("termsTextVersion", "2026-11-13");
    body.put("privacyNoticeTextVersion", "2026-11-14");

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
            Map.entry("AGE_14_CONFIRMED", "2026-11-12"),
            Map.entry("TERMS_AGREED", "2026-11-13"),
            Map.entry("PRIVACY_NOTICE_ACK", "2026-11-14"));
    assertThat(response).doesNotContain(phone).doesNotContain("abc12345").doesNotContain(hash);
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

  // 도움

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
