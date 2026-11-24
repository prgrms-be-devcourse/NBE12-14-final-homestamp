package com.chilsuitjo.back.global.security;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.config.ClockConfig;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code prod}가 켜지면 {@code local}이 함께 켜져도 API 문서 주소를 로그인 없이 열지 않는다(04 API 문서). prod는 키 없이 시작하지 않으므로
 * 운영처럼 설정으로 키를 넣는다. 이때 local·test 고정 키로 만든 토큰은 받지 않는다.
 */
abstract class SecurityProdProfileTest {

  @BeforeAll
  static void provideExplicitFakeSsmSettings() {
    SsmTestParameterSource.setParameters(SsmTestParameterSource.validParameters());
  }

  @AfterAll
  static void resetFakeSsmSettings() {
    SsmTestParameterSource.reset();
  }

  @Autowired private MockMvc mvc;

  @Test
  void API_문서_주소는_로그인이_필요하다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
  }

  @Test
  void local_test_고정_키로_만든_토큰은_받지_않는다() throws Exception {
    mvc.perform(get("/api/me").with(loginAs(UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @WebMvcTest(SecurityTestControllerConfig.SecurityTestController.class)
  @Import({
    SecurityTestControllerConfig.class,
    SecurityConfig.class,
    AccessTokenConfig.class,
    ClockConfig.class
  })
  @TestPropertySource(
      properties = {
        "app.keys.access-token=prod-like-access-token-signing-key-for-test",
        "app.keys.refresh-token=prod-like-refresh-token-rotation-key-for-test"
      })
  @ActiveProfiles("prod")
  static class Prod extends SecurityProdProfileTest {}

  @WebMvcTest(SecurityTestControllerConfig.SecurityTestController.class)
  @Import({
    SecurityTestControllerConfig.class,
    SecurityConfig.class,
    AccessTokenConfig.class,
    ClockConfig.class
  })
  @TestPropertySource(
      properties = {
        "app.keys.access-token=prod-like-access-token-signing-key-for-test",
        "app.keys.refresh-token=prod-like-refresh-token-rotation-key-for-test"
      })
  @ActiveProfiles({"local", "prod"})
  static class LocalProd extends SecurityProdProfileTest {}
}
