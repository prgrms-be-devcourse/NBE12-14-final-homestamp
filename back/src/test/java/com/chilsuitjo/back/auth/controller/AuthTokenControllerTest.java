package com.chilsuitjo.back.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.service.AuthTokenService;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 06 4-1 {@code POST /api/auth/refresh}·{@code /logout}. 보안 설정까지 실제로 띄워 쿠키 속성(04 4-1 브라우저 보관),
 * Origin 확인(04 4-1 재발급 요청 보호), 오류 모양(06 4-10)을 본다. 회원 확인이 필요한 로그인 API는 회원가입(#106) 뒤에 더하므로 첫 토큰은
 * 서비스로 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTokenControllerTest {

  private static final String ORIGIN = "http://localhost:5173";

  @Autowired private MockMvc mvc;
  @Autowired private AuthTokenService service;
  @Autowired private AccessTokens accessTokens;
  @Autowired private JdbcTemplate jdbc;

  private UUID memberId;

  @BeforeEach
  void setUp() {
    memberId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', ?, 0)",
        memberId,
        Timestamp.from(Instant.now()));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM state.refresh_token");
    jdbc.update("DELETE FROM state.member WHERE id = ?", memberId);
  }

  @Test
  void 재발급하면_본문에_액세스_토큰_쿠키에_다음_리프레시_토큰을_준다() throws Exception {
    IssuedTokens login = service.issue(memberId);

    MvcResult result =
        mvc.perform(refresh(login.refreshToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();

    String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(cookie)
        .startsWith("refresh_token=")
        .doesNotContain(login.refreshToken())
        .contains("Path=/api/auth/refresh")
        .contains("Max-Age=1209600")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Strict");
    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    assertThat(accessTokens.verify(accessToken)).contains(memberId);
  }

  @Test
  void ACC_2b_같은_쿠키로_바로_다시_재발급하면_같은_리프레시_쿠키를_받는다() throws Exception {
    IssuedTokens login = service.issue(memberId);

    String first = refreshCookieValue(mvc.perform(refresh(login.refreshToken())));
    String again = refreshCookieValue(mvc.perform(refresh(login.refreshToken())));

    assertThat(again).isEqualTo(first);
  }

  @Test
  void Origin이_없거나_다르면_LOGIN_REQUIRED() throws Exception {
    IssuedTokens login = service.issue(memberId);

    expectProblem(
        mvc.perform(
            post("/api/auth/refresh")
                .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, login.refreshToken()))),
        ErrorCode.LOGIN_REQUIRED);
    expectProblem(
        mvc.perform(
            post("/api/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, login.refreshToken()))),
        ErrorCode.LOGIN_REQUIRED);

    // 거절한 요청은 토큰을 바꾸지 않았다
    mvc.perform(refresh(login.refreshToken())).andExpect(status().isOk());
  }

  @Test
  void 쿠키가_없거나_모르는_토큰이면_LOGIN_REQUIRED() throws Exception {
    expectProblem(
        mvc.perform(post("/api/auth/refresh").header(HttpHeaders.ORIGIN, ORIGIN)),
        ErrorCode.LOGIN_REQUIRED);
    expectProblem(mvc.perform(refresh("모르는-토큰")), ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void 로그아웃하면_204와_같은_Path로_쿠키를_지우고_그_리프레시_토큰은_거절된다() throws Exception {
    IssuedTokens login = service.issue(memberId);

    String cleared =
        mvc.perform(
                post("/api/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.SET_COOKIE);

    assertThat(cleared)
        .startsWith("refresh_token=;")
        .contains("Path=/api/auth/refresh")
        .contains("Max-Age=0")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict");
    expectProblem(mvc.perform(refresh(login.refreshToken())), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 재발급으로_받은_액세스_토큰으로도_로그아웃된다() throws Exception {
    IssuedTokens login = service.issue(memberId);
    MvcResult refreshed = mvc.perform(refresh(login.refreshToken())).andReturn();
    String accessToken =
        JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
    String nextRefresh = refreshCookieValue(refreshed);

    mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isNoContent());

    expectProblem(mvc.perform(refresh(nextRefresh)), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 로그아웃은_로그인이_필요하다() throws Exception {
    expectProblem(mvc.perform(post("/api/auth/logout")), ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void 재사용이면_REFRESH_REUSED_모양으로_답한다() throws Exception {
    IssuedTokens login = service.issue(memberId);
    service.logout(memberId, accessTokens.read(login.accessToken()).orElseThrow().familyId());

    expectProblem(mvc.perform(refresh(login.refreshToken())), ErrorCode.REFRESH_REUSED)
        .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
  }

  private static MockHttpServletRequestBuilder refresh(String refreshToken) {
    return post("/api/auth/refresh")
        .header(HttpHeaders.ORIGIN, ORIGIN)
        .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, refreshToken));
  }

  private static String refreshCookieValue(ResultActions result) throws Exception {
    return refreshCookieValue(result.andExpect(status().isOk()).andReturn());
  }

  private static String refreshCookieValue(MvcResult result) {
    List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    assertThat(cookies).hasSize(1);
    String cookie = cookies.getFirst();
    return cookie.substring("refresh_token=".length(), cookie.indexOf(';'));
  }

  private static ResultActions expectProblem(ResultActions result, ErrorCode code)
      throws Exception {
    return result
        .andExpect(status().is(code.status().value()))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.detail").value(code.message()));
  }
}
