package com.chilsuitjo.back.global.security;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.config.ClockConfig;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * test 프로필: local·test 고정 키의 실제 JWT 검증기로 {@code Bearer <액세스 JWT>}가 로그인이 된다(06 4-0 인증, 4-10). 예전 가짜
 * 검증기의 {@code Bearer <회원 UUID>}는 더 이상 받지 않는다.
 */
@WebMvcTest(SecurityTestControllerConfig.SecurityTestController.class)
@Import({
  SecurityTestControllerConfig.class,
  SecurityConfig.class,
  AccessTokenConfig.class,
  ClockConfig.class
})
@ActiveProfiles("test")
class SecurityTestProfileTest {

  @Autowired private MockMvc mvc;

  @Test
  void 맞는_토큰이면_LoginMember에_그_회원_ID가_들어온다() throws Exception {
    UUID memberId = UUID.randomUUID();

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(status().isOk())
        .andExpect(content().string(memberId.toString()));
  }

  @Test
  void 토큰이_없으면_401_LOGIN_REQUIRED() throws Exception {
    mvc.perform(get("/api/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
        .andExpect(jsonPath("$.detail").value(ErrorCode.LOGIN_REQUIRED.message()));
  }

  @Test
  void 틀린_토큰이면_401_LOGIN_REQUIRED() throws Exception {
    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-uuid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void 예전_가짜_토큰_모양인_회원_UUID만으로는_401() throws Exception {
    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void Bearer가_아닌_인증_머리는_401() throws Exception {
    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Basic " + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 공개_API는_토큰_없이_된다() throws Exception {
    mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
    mvc.perform(post("/api/public/links/view")).andExpect(status().isOk());
  }

  @Test
  void 공개_API에_틀린_토큰이_와도_막지_않는다() throws Exception {
    mvc.perform(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer 만료된-토큰"))
        .andExpect(status().isOk());
  }

  @Test
  void 공개_주소라도_메서드가_다르면_로그인이_필요하다() throws Exception {
    mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
  }

  @Test
  void 가입_전_링크_주소도_POST가_아니면_401() throws Exception {
    mvc.perform(get("/api/public/links/view"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void 공개_API에서_LoginMember를_쓰면_로그인_없이는_LOGIN_REQUIRED() throws Exception {
    mvc.perform(post("/api/auth/signup"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void test_프로필에서는_API_문서_주소를_막지_않는다() throws Exception {
    // @WebMvcTest 범위에서는 springdoc이 문서 주소를 등록하지 않아 404가 난다. 401이 아니라 Security를 통과했다는 뜻이다
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
  }

  @Test
  void 세션을_만들지_않는다() throws Exception {
    mvc.perform(get("/api/me").with(loginAs(UUID.randomUUID())))
        .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
  }
}
