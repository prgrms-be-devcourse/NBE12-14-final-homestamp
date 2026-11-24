package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

class SsmConfigDataIntegrationTest {

  private static final String PREFIX = SsmAppParameterMapper.PARAMETER_PREFIX;

  @AfterEach
  void resetTestSource() {
    SsmTestParameterSource.reset();
  }

  @Test
  void prod_ConfigData가_일반_Bean_생성_전에_설정을_공급한다() {
    SsmTestParameterSource.setParameters(requiredParameters());

    try (ConfigurableApplicationContext context = run("prod")) {
      DriverManagerDataSource dataSource = context.getBean(DriverManagerDataSource.class);
      ProbeBean probe = context.getBean(ProbeBean.class);
      assertEquals(
          "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full", dataSource.getUrl());
      assertEquals("app-user", dataSource.getUsername());
      assertEquals("db-password-secret", dataSource.getPassword());
      assertEquals("https://homestamp.example", probe.origin());
      assertEquals(
          false,
          context
              .getEnvironment()
              .getPropertySources()
              .get("homestampSsmApp")
              .toString()
              .contains("db-password-secret"));
      assertEquals(1, SsmTestParameterSource.calls());
      assertEquals(0, SsmTestParameterSource.migrationCalls());
    }
  }

  @Test
  void local과_test는_SSM_호출이_없다() {
    for (String profile : List.of("local", "test")) {
      SsmTestParameterSource.reset();
      try (ConfigurableApplicationContext context = run(profile)) {
        assertEquals(0, SsmTestParameterSource.calls());
        assertEquals(0, SsmTestParameterSource.migrationCalls());
        assertEquals("http://localhost:5173", context.getEnvironment().getProperty("app.origin"));
      }
    }
  }

  @Test
  void prod와_test가_함께_활성화되어도_SSM을_필수_로딩한다() {
    SsmTestParameterSource.setParameters(requiredParameters());

    try (ConfigurableApplicationContext context = run("prod", "test")) {
      assertEquals(
          "db-password-secret", context.getBean(DriverManagerDataSource.class).getPassword());
      assertEquals(1, SsmTestParameterSource.calls());
      assertEquals(0, SsmTestParameterSource.migrationCalls());
    }
  }

  @Test
  void prod와_test가_함께_활성화되어도_테스트_기본값으로_대체하지_않는다() {
    SsmTestParameterSource.setParameters(List.of());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> run("prod", "test"));

    assertEquals(false, error.getMessage().contains("http://localhost:5173"));
    assertEquals(1, SsmTestParameterSource.calls());
  }

  @Test
  void prod에서_필수_SSM_설정이_없으면_시작을_중단한다() {
    SsmTestParameterSource.setParameters(List.of());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> run("prod"));

    assertEquals(false, error.getMessage().contains("db-password-secret"));
    assertEquals(1, SsmTestParameterSource.calls());
  }

  @Test
  void SSM_API_타임아웃은_비밀값을_노출하지_않고_prod_시작을_중단한다() {
    SsmTestParameterSource.setFailure(
        ApiCallTimeoutException.create("simulated timeout with fake-secret", null));

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> run("prod"));

    assertEquals("운영 앱 SSM 설정 조회 시간이 초과됐다", error.getMessage());
    assertEquals(false, error.toString().contains("fake-secret"));
    assertEquals(1, SsmTestParameterSource.calls());
  }

  private static ConfigurableApplicationContext run(String... profiles) {
    SpringApplication application = new SpringApplication(ConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles(profiles);
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    return application.run();
  }

  private static List<SsmConfigParameter> requiredParameters() {
    List<SsmConfigParameter> parameters = new ArrayList<>();
    parameters.add(
        parameter(
            "spring.datasource.url",
            "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full",
            "String"));
    parameters.add(parameter("spring.datasource.username", "app-user", "SecureString"));
    parameters.add(parameter("spring.datasource.password", "db-password-secret", "SecureString"));
    parameters.add(parameter("app.keys.verify-code", "verify-code-secret", "SecureString"));
    parameters.add(parameter("app.keys.verify-ticket", "verify-ticket-secret", "SecureString"));
    parameters.add(parameter("app.keys.phone-hmac", "phone-hmac-secret", "SecureString"));
    parameters.add(
        parameter(
            "app.keys.access-token",
            "access-token-secret-which-is-long-enough-0001",
            "SecureString"));
    parameters.add(
        parameter(
            "app.keys.refresh-token",
            "refresh-token-secret-which-is-long-enough-0002",
            "SecureString"));
    parameters.add(parameter("app.origin", "https://homestamp.example", "String"));
    return parameters;
  }

  private static SsmConfigParameter parameter(String key, String value, String type) {
    return new SsmConfigParameter(PREFIX + key, value, type);
  }

  @Configuration(proxyBeanMethods = false)
  static class ConfigProbe {

    @Bean
    DriverManagerDataSource dataSource(
        @Value("${spring.datasource.url:}") String url,
        @Value("${spring.datasource.username:}") String username,
        @Value("${spring.datasource.password:}") String password) {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setUrl(url);
      dataSource.setUsername(username);
      dataSource.setPassword(password);
      return dataSource;
    }

    @Bean
    ProbeBean probeBean(@Value("${app.origin:}") String origin) {
      return new ProbeBean(origin);
    }
  }

  record ProbeBean(String origin) {}
}
