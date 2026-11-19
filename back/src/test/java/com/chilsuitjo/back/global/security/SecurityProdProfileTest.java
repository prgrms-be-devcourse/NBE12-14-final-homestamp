package com.chilsuitjo.back.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code prod}가 켜지면 {@code local}이 함께 켜져도 API 문서 주소를 로그인 없이 열지 않는다(04 API 문서). prod는 실제 검증기 없이 시작하지
 * 않으므로 아무 토큰도 받지 않는 테스트용 검증기를 넣는다.
 */
abstract class SecurityProdProfileTest {

  @Autowired private MockMvc mvc;

  @Test
  void API_문서_주소는_로그인이_필요하다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
  }

  @TestConfiguration
  static class 받지_않는_검증기 {
    @Bean
    AccessTokenVerifier accessTokenVerifier() {
      return token -> Optional.<UUID>empty();
    }
  }

  @WebMvcTest(SecurityTestController.class)
  @Import({SecurityConfig.class, FakeAccessTokenVerifier.class, 받지_않는_검증기.class})
  @ActiveProfiles("prod")
  static class Prod extends SecurityProdProfileTest {}

  @WebMvcTest(SecurityTestController.class)
  @Import({SecurityConfig.class, FakeAccessTokenVerifier.class, 받지_않는_검증기.class})
  @ActiveProfiles({"local", "prod"})
  static class LocalProd extends SecurityProdProfileTest {}
}
