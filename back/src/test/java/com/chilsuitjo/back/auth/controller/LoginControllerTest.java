package com.chilsuitjo.back.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 06 4-1 {@code POST /api/auth/login}과 ACC-2a·2d 수용 기준. 보안 설정까지 띄워 로그인 없이 부른다. 회원은 가입 API 대신 DB에 바로
 * 넣는다(가입은 {@code SignupControllerTest}가 본다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LoginControllerTest {

  private static final String ORIGIN = "http://localhost:5173";
  private static final String PASSWORD = "homestamp123";

  @Autowired private MockMvc mvc;
  @Autowired private AccessTokens accessTokens;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    for (UUID id : created) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", id);
      jdbc.update("DELETE FROM state.member WHERE id = ?", id);
    }
  }

  @Test
  void ACC_2a_로그인하면_짧은_액세스_토큰과_리프레시_쿠키를_받는다() throws Exception {
    String email = newEmail();
    UUID memberId = activeMember(email, PASSWORD);

    MvcResult result =
        mvc.perform(login(email, PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();

    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    assertThat(accessTokens.verify(accessToken)).contains(memberId);
    String payload =
        new String(
            Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    long issuedAt = ((Number) JsonPath.read(payload, "$.iat")).longValue();
    long expiresAt = ((Number) JsonPath.read(payload, "$.exp")).longValue();
    assertThat(expiresAt - issuedAt).isEqualTo(15 * 60);

    List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    assertThat(cookies).hasSize(1);
    assertThat(cookies.getFirst())
        .startsWith("refresh_token=")
        .contains("Path=/api/auth/refresh")
        .contains("Max-Age=1209600")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict");
  }

  @Test
  void 로그인으로_받은_리프레시_쿠키로_재발급된다() throws Exception {
    String email = newEmail();
    UUID memberId = activeMember(email, PASSWORD);
    String refreshToken = refreshCookieValue(mvc.perform(login(email, PASSWORD)).andReturn());

    MvcResult refreshed =
        mvc.perform(
                post("/api/auth/refresh")
                    .header(HttpHeaders.ORIGIN, ORIGIN)
                    .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, refreshToken)))
            .andExpect(status().isOk())
            .andReturn();

    String accessToken =
        JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
    assertThat(accessTokens.verify(accessToken)).contains(memberId);
  }

  @Test
  void 이메일은_대소문자를_가리지_않는다() throws Exception {
    String email = newEmail();
    UUID memberId = activeMember(email, PASSWORD);

    MvcResult result =
        mvc.perform(login(email.toUpperCase(), PASSWORD)).andExpect(status().isOk()).andReturn();

    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    assertThat(accessTokens.verify(accessToken)).contains(memberId);
  }

  @Test
  void 로그인할_때마다_새_묶음이라_한_기기의_로그아웃이_다른_기기에_닿지_않는다() throws Exception {
    String email = newEmail();
    activeMember(email, PASSWORD);
    MvcResult phone = mvc.perform(login(email, PASSWORD)).andReturn();
    MvcResult laptop = mvc.perform(login(email, PASSWORD)).andReturn();
    String phoneAccess = JsonPath.read(phone.getResponse().getContentAsString(), "$.accessToken");
    String laptopAccess = JsonPath.read(laptop.getResponse().getContentAsString(), "$.accessToken");

    assertThat(accessTokens.read(phoneAccess).orElseThrow().familyId())
        .isNotEqualTo(accessTokens.read(laptopAccess).orElseThrow().familyId());

    mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + phoneAccess))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/api/auth/refresh")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, refreshCookieValue(laptop))))
        .andExpect(status().isOk());
  }

  @Test
  void ACC_2d_로그인_실패는_어느_쪽이_틀렸는지_말하지_않는다() throws Exception {
    String email = newEmail();
    activeMember(email, PASSWORD);

    String wrongPassword =
        expectLoginFailed(mvc.perform(login(email, "wrong12345")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknownEmail =
        expectLoginFailed(mvc.perform(login(newEmail(), PASSWORD)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(unknownEmail).isEqualTo(wrongPassword);
  }

  @Test
  void 탈퇴한_회원은_LOGIN_FAILED() throws Exception {
    String email = newEmail();
    UUID memberId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, status, withdrawn_at, created_at,"
            + " version) VALUES (?, ?, ?, 'WITHDRAWN', ?, ?, 0)",
        memberId,
        email,
        passwordEncoder.encode(PASSWORD),
        now,
        now);
    created.add(memberId);

    expectLoginFailed(mvc.perform(login(email, PASSWORD)));
  }

  @Test
  void BCrypt가_받지_못하는_긴_비밀번호도_LOGIN_FAILED() throws Exception {
    String email = newEmail();
    activeMember(email, PASSWORD);

    expectLoginFailed(mvc.perform(login(email, PASSWORD + "a".repeat(72))));
  }

  @Test
  void 빈_칸이면_VALIDATION_FAILED() throws Exception {
    mvc.perform(login("", PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    mvc.perform(login(newEmail(), ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
  }

  @Test
  void 실패한_비밀번호는_로그에_남지_않는다(CapturedOutput output) throws Exception {
    String email = newEmail();
    activeMember(email, PASSWORD);
    String secret = "secret-wrong-7788";

    expectLoginFailed(mvc.perform(login(email, secret)));
    mvc.perform(login("", secret)).andExpect(status().isBadRequest());

    assertThat(output.getAll()).doesNotContain(secret);
  }

  private UUID activeMember(String email, String password) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, status, created_at,"
            + " version) VALUES (?, ?, ?, '서윤', 'ACTIVE', ?, 0)",
        id,
        email,
        passwordEncoder.encode(password),
        now);
    created.add(id);
    return id;
  }

  private static String newEmail() {
    return "login-" + UUID.randomUUID() + "@example.com";
  }

  /** 테스트 값에는 따옴표·역슬래시가 없어 그대로 넣는다. */
  private static MockHttpServletRequestBuilder login(String email, String password) {
    String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String refreshCookieValue(MvcResult result) {
    String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(cookie).isNotNull();
    return cookie.substring("refresh_token=".length(), cookie.indexOf(';'));
  }

  private static ResultActions expectLoginFailed(ResultActions result) throws Exception {
    return result
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_FAILED.name()))
        .andExpect(jsonPath("$.detail").value(ErrorCode.LOGIN_FAILED.message()))
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }
}
