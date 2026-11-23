package com.chilsuitjo.back.global.security;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.config.ClockConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프로필이 없으면 로그인 토큰 키가 없어 검증기가 켜지지 않는다. 서버는 뜨지만 test 키로 서명한 실제 JWT로도 로그인할 수 없고, API 문서 주소도 열지 않는다.
 * {@link AccessTokenConfig}를 일부러 가져와도 키 조건이 막는지 본다.
 */
@WebMvcTest(SecurityTestControllerConfig.SecurityTestController.class)
@Import({
  SecurityTestControllerConfig.class,
  SecurityConfig.class,
  AccessTokenConfig.class,
  ClockConfig.class
})
class SecurityNoProfileTest {

  @Autowired private MockMvc mvc;
  @Autowired private ApplicationContext context;

  @Test
  void 키가_없으면_검증기가_없다() {
    assertThat(context.getBeanNamesForType(AccessTokenVerifier.class)).isEmpty();
  }

  @Test
  void test_키로_서명한_JWT로도_401() throws Exception {
    mvc.perform(get("/api/me").with(loginAs(UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void 예전_가짜_토큰_모양도_401() throws Exception {
    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 공개_API는_그대로_된다() throws Exception {
    mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
  }

  @Test
  void API_문서_주소는_로그인이_필요하다() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
  }
}
