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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * 06 4-1 {@code POST /api/auth/phone-verifications/{id}/verify}. 보안 설정(#80)까지 실제로 띄워 로그인 없이 부르고,
 * 오류는 06 4-10 모양으로 돌려주며, 응답에 원문 인증번호·비밀 키가 나가지 않는지 본다. 인증번호는 서비스로 만든다(보내기 API는 D 계약 뒤).
 */
@Import({TestcontainersConfiguration.class, PhoneVerificationControllerTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhoneVerificationControllerTest {

  private static final Instant START = Instant.parse("2026-11-19T01:00:00Z");
  private static final String PHONE = "01012345678";

  @Autowired private MockMvc mvc;
  @Autowired private PhoneVerificationService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MutableClock clock;

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
