package com.chilsuitjo.back.auth.controller;

import static com.chilsuitjo.back.auth.rule.VerificationPurpose.SIGNUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.service.PhoneVerificationService;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import com.chilsuitjo.back.notification.service.FakeVerificationSmsSender;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 06 4-1 {@code POST /api/auth/phone-verifications}·{@code .../{id}/verify}. 보안 설정(#80)까지 실제로 띄워
 * 로그인 없이 부르고, 오류는 06 4-10 모양으로 돌려주며, 응답·로그에 원문 인증번호·번호·비밀 키가 나가지 않는지 본다. 문자는 D의 가짜 발송기가 받고, 결과는
 * {@code respondWith}로 정한다(06 ACC-1j).
 */
@Import({TestcontainersConfiguration.class, PhoneVerificationControllerTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PhoneVerificationControllerTest {

  private static final Instant START = Instant.parse("2026-11-19T01:00:00Z");
  private static final String PHONE = "01012345678";

  @Autowired private MockMvc mvc;
  @Autowired private PhoneVerificationService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;
  @Autowired private FakeVerificationSmsSender sender;

  @Value("${app.keys.verify-code}")
  private String codeKey;

  @Value("${app.keys.verify-ticket}")
  private String ticketKey;

  @Value("${app.keys.phone-hmac}")
  private String phoneKey;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM state.phone_verification");
    jdbc.update("DELETE FROM state.rate_counter");
    clock.set(START);
    sender.respondWith(VerificationSendResult.SENT);
  }

  @AfterEach
  void resetSender() {
    // 가짜 발송기는 같은 컨텍스트를 쓰는 다른 테스트 클래스와 공유된다
    sender.respondWith(VerificationSendResult.SENT);
  }

  @Test
  void 로그인_없이_보내면_201과_인증_ID만_받고_받은_번호로_인증할_수_있다() throws Exception {
    String body =
        send("010-1234-5678", "SIGNUP")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String code = sender.lastCodeFor(PHONE).orElseThrow();
    assertThat(body).doesNotContain(code).doesNotContain(PHONE).doesNotContain("1234-5678");
    assertThat(rows()).isEqualTo(1);

    verify(idOf(body), code).andExpect(status().isOk()).andExpect(jsonPath("$.ticket").isString());
  }

  @Test
  void 공개_API라_틀린_로그인_토큰이_와도_보낼_수_있다() throws Exception {
    mvc.perform(
            post("/api/auth/phone-verifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer 만료된-토큰")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendJson(PHONE, "RESET")))
        .andExpect(status().isCreated());
  }

  @Test
  void 업체가_실패로_답하면_503_SMS_SEND_FAILED이고_행이_남지_않아_바로_다시_받을_수_있다(CapturedOutput output)
      throws Exception {
    sender.respondWith(VerificationSendResult.FAILED);

    String body =
        expectProblem(send(PHONE, "SIGNUP"), ErrorCode.SMS_SEND_FAILED)
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(PHONE);
    assertThat(rows()).isZero();
    assertThat(output.getAll()).contains("인증 문자 발송 실패").doesNotContain(PHONE);

    // 직전 발송이 실패였으면 다시 받기 간격에 걸리지 않는다(ACC-1i)
    sender.respondWith(VerificationSendResult.SENT);
    send(PHONE, "SIGNUP").andExpect(status().isCreated());
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void 결과를_모르면_보낸_것으로_보고_인증번호를_그대로_둔다(CapturedOutput output) throws Exception {
    sender.respondWith(VerificationSendResult.UNKNOWN);

    String body =
        send(PHONE, "SIGNUP")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String code = sender.lastCodeFor(PHONE).orElseThrow();
    assertThat(rows()).isEqualTo(1);
    assertThat(output.getAll())
        .contains("인증 문자 발송 결과 모름")
        .doesNotContain(PHONE)
        .doesNotContain(code);

    // 보낸 것으로 봐서 다시 받기 간격에 걸리고, 그 번호로 인증할 수 있다
    expectProblem(send(PHONE, "SIGNUP"), ErrorCode.RATE_LIMITED)
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
    verify(idOf(body), code).andExpect(status().isOk());
  }

  @Test
  void 간격_안에_다시_받으면_429_RATE_LIMITED와_Retry_After() throws Exception {
    send(PHONE, "SIGNUP").andExpect(status().isCreated());
    clock.advance(Duration.ofSeconds(20));

    expectProblem(send(PHONE, "SIGNUP"), ErrorCode.RATE_LIMITED)
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "40"));
    assertThat(rows()).isEqualTo(1);

    // 목적이 다르면 다른 진행이라 간격에 걸리지 않는다
    send(PHONE, "RESET").andExpect(status().isCreated());
  }

  @Test
  void 같은_번호로_하루_다섯_번을_넘게_받으면_429_DAILY_LIMIT이고_보내지_않는다() throws Exception {
    for (int i = 0; i < 5; i++) {
      send(PHONE, "SIGNUP").andExpect(status().isCreated());
      clock.advance(Duration.ofSeconds(61));
    }
    String before = sender.lastCodeFor(PHONE).orElseThrow();

    expectProblem(send(PHONE, "SIGNUP"), ErrorCode.DAILY_LIMIT);
    assertThat(rows()).isEqualTo(5);
    assertThat(sender.lastCodeFor(PHONE)).contains(before);
  }

  @Test
  void 번호나_목적이_틀리면_400_VALIDATION_FAILED이고_보내지_않는다() throws Exception {
    // 가짜 발송기의 마지막 번호는 다른 테스트와 공유되므로 이 테스트만 쓰는 번호로 본다
    String phone = "01055556666";
    expectProblem(send("02-123-4567", "SIGNUP"), ErrorCode.VALIDATION_FAILED);
    expectProblem(send(" ", "SIGNUP"), ErrorCode.VALIDATION_FAILED);
    expectProblem(send(phone, "LOGIN"), ErrorCode.VALIDATION_FAILED);
    expectProblem(
        mvc.perform(
            post("/api/auth/phone-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}")),
        ErrorCode.VALIDATION_FAILED);

    assertThat(rows()).isZero();
    assertThat(sender.lastCodeFor(phone)).isEmpty();
  }

  @Test
  void 로그인_없이_맞는_번호를_넣으면_인증_표를_받는다() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);

    String body =
        verify(code.verificationId(), code.code())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.ticket").isString())
            .andExpect(jsonPath("$.expiresAt").value("2026-11-19T01:15:00Z"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .doesNotContain(code.code())
        .doesNotContain(PHONE)
        .doesNotContain(codeKey)
        .doesNotContain(ticketKey)
        .doesNotContain(phoneKey);
  }

  @Test
  void 공개_API라_틀린_로그인_토큰이_와도_막지_않는다() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);

    mvc.perform(
            post("/api/auth/phone-verifications/{id}/verify", code.verificationId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer 만료된-토큰")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(code.code())))
        .andExpect(status().isOk());
  }

  @Test
  void POST가_아니면_로그인이_필요하다() throws Exception {
    mvc.perform(get("/api/auth/phone-verifications/{id}/verify", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void 틀린_번호는_400_CODE_INVALID이고_응답에_번호가_없다() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    String wrong = code.code().equals("000000") ? "111111" : "000000";

    String body =
        expectProblem(verify(code.verificationId(), wrong), ErrorCode.CODE_INVALID)
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(code.code()).doesNotContain(PHONE);
  }

  @Test
  void 다시_받은_뒤_이전_화면에서_넣으면_400_CODE_REPLACED() throws Exception {
    IssuedCode first = service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofSeconds(61));
    service.issue(PHONE, SIGNUP);

    expectProblem(verify(first.verificationId(), first.code()), ErrorCode.CODE_REPLACED);
  }

  @Test
  void 시간이_지났거나_없는_진행이면_400_CODE_EXPIRED() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);
    clock.advance(Duration.ofMinutes(5));

    expectProblem(verify(code.verificationId(), code.code()), ErrorCode.CODE_EXPIRED);
    expectProblem(verify(UUID.randomUUID(), code.code()), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 번호가_비었거나_주소의_ID가_틀리면_VALIDATION_FAILED() throws Exception {
    IssuedCode code = service.issue(PHONE, SIGNUP);

    expectProblem(verify(code.verificationId(), " "), ErrorCode.VALIDATION_FAILED);
    expectProblem(
        mvc.perform(
            post("/api/auth/phone-verifications/not-a-uuid/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("123456"))),
        ErrorCode.VALIDATION_FAILED);
  }

  private ResultActions verify(UUID id, String code) throws Exception {
    return mvc.perform(
        post("/api/auth/phone-verifications/{id}/verify", id)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(code)));
  }

  private static String json(String code) {
    return "{\"code\":\"" + code + "\"}";
  }

  private ResultActions send(String phone, String purpose) throws Exception {
    return mvc.perform(
        post("/api/auth/phone-verifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(sendJson(phone, purpose)));
  }

  private static String sendJson(String phone, String purpose) {
    return "{\"phone\":\"" + phone + "\",\"purpose\":\"" + purpose + "\"}";
  }

  private static UUID idOf(String body) {
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private int rows() {
    return jdbc.queryForObject("SELECT count(*) FROM state.phone_verification", Integer.class);
  }

  private static ResultActions expectProblem(ResultActions result, ErrorCode code)
      throws Exception {
    return result
        .andExpect(status().is(code.status().value()))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.detail").value(code.message()));
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
