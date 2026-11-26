package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@ExtendWith({ResetSsmTestParameterSource.class, OutputCaptureExtension.class})
class SsmConfigDataIntegrationTest {

  private static final String PREFIX = SsmAppParameterMapper.PARAMETER_PREFIX;

  @Test
  void prod_ConfigData가_일반_Bean_생성_전에_설정을_공급한다() {
    SsmTestParameterSource.setParameters(requiredParameters());

    try (ConfigurableApplicationContext context = run("prod")) {
      DriverManagerDataSource dataSource = context.getBean(DriverManagerDataSource.class);
      ProbeBean probe = context.getBean(ProbeBean.class);
      assertEquals(
          "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full&sslrootcert="
              + SsmTestParameterSource.ROOT_CERT,
          dataSource.getUrl());
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
    }
  }

  @Test
  void local과_test는_SSM_호출이_없다() {
    for (String profile : List.of("local", "test")) {
      SsmTestParameterSource.reset();
      try (ConfigurableApplicationContext context = run(profile)) {
        assertEquals(0, SsmTestParameterSource.calls());
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
  void Source가_분류한_오류는_그대로_전달하고_prod_시작을_중단한다() {
    // 운영 Source(AwsSsmParameterSource)는 SDK 예외를 SsmConfigException으로 바꿔 던진다(분류는
    // AwsSsmParameterSourceTest)
    SsmTestParameterSource.setFailure(new SsmConfigException("운영 앱 SSM 설정 조회 시간이 초과됐다"));

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> run("prod"));

    assertEquals("운영 앱 SSM 설정 조회 시간이 초과됐다", error.getMessage());
    assertEquals(1, SsmTestParameterSource.calls());
  }

  @Test
  void 분류되지_않은_오류는_클래스_이름만_남기고_prod_시작을_중단한다() {
    SsmTestParameterSource.setFailure(new IllegalArgumentException("unexpected fake-secret"));

    SsmConfigException error = assertThrows(SsmConfigException.class, () -> run("prod"));

    assertEquals("운영 앱 SSM 설정을 불러오거나 검증하지 못했다: IllegalArgumentException", error.getMessage());
    assertNull(error.getCause());
    assertEquals(false, error.toString().contains("fake-secret"));
  }

  /**
   * 운영 jar와 같은 설정 파일만 읽고 본다. 테스트 클래스패스의 {@code config/application.yml}(테스트 전용 키 5개)은 Spring 규칙상
   * {@code application.yml}과 그 import(SSM)보다 앞서서, 그대로 띄우면 그 키들이 SSM 값을 가린다. 운영 jar에는 그 파일이 없다.
   */
  @Test
  void prod는_12개_필수_설정을_모두_Environment에_공급한다() {
    SsmTestParameterSource.setParameters(requiredParameters());

    try (ConfigurableApplicationContext context = runWithMainConfigOnly("prod")) {
      var environment = context.getEnvironment();
      for (SsmConfigParameter parameter : requiredParameters()) {
        String key = parameter.name().substring(PREFIX.length());
        assertEquals(parameter.value(), environment.getProperty(key), key);
      }
      assertEquals(12, ssmPropertyNames(context).length);
    }
  }

  @Test
  void 목록_밖_SSM_키가_있어도_시작하고_Environment에_넣지_않으며_이름만_WARN으로_남긴다(CapturedOutput output) {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.add(parameter("future.feature.key", "future-secret-value", "SecureString"));
    SsmTestParameterSource.setParameters(parameters);

    try (ConfigurableApplicationContext context = run("prod")) {
      assertNull(context.getEnvironment().getProperty("future.feature.key"));
      assertFalse(Arrays.asList(ssmPropertyNames(context)).contains("future.feature.key"));
      assertEquals(1, SsmTestParameterSource.calls());
    }
    assertTrue(output.getAll().contains("허용 목록에 없는 SSM 앱 설정을 적용하지 않았다"));
    assertTrue(output.getAll().contains("future.feature.key"));
    assertFalse(output.getAll().contains("future-secret-value"));
  }

  @Test
  void SSM에_넣은_Spring_실행_정책은_적용되지_않는다(CapturedOutput output) {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.add(parameter("spring.profiles.active", "local", "String"));
    parameters.add(parameter("spring.profiles.include", "test", "String"));
    parameters.add(parameter("spring.config.import", "optional:file:/tmp/evil.yml", "String"));
    parameters.add(parameter("spring.flyway.enabled", "true", "String"));
    parameters.add(parameter("spring.main.web-application-type", "servlet", "String"));
    parameters.add(parameter("app.sms.mode", "fake", "String"));
    SsmTestParameterSource.setParameters(parameters);

    try (ConfigurableApplicationContext context = run("prod")) {
      var environment = context.getEnvironment();
      assertArrayEquals(new String[] {"prod"}, environment.getActiveProfiles());
      assertNull(environment.getProperty("spring.profiles.include"));
      assertNull(environment.getProperty("spring.flyway.enabled"));
      assertNull(environment.getProperty("spring.main.web-application-type"));
      // yml prod 기본값(#216)이 남고 SSM의 fake는 적용되지 않는다
      assertEquals("disabled", environment.getProperty("app.sms.mode"));
      // local 문서의 고정 화면 주소가 섞이지 않고 SSM 값 그대로
      assertEquals("https://homestamp.example", environment.getProperty("app.origin"));
      assertEquals(12, ssmPropertyNames(context).length);
    }
    assertFalse(output.getAll().contains("optional:file:/tmp/evil.yml"));
  }

  @Test
  void 테스트_config_파일이_SSM_값을_덮으면_prod는_시작하지_않는다() {
    // 테스트 클래스패스의 config/application.yml은 app.keys.verify-code를 SSM보다 우선해 정한다
    SsmTestParameterSource.setParameters(
        with(requiredParameters(), "app.keys.verify-code", "different-ssm-verify-code"));

    SsmConfigException error = assertThrows(SsmConfigException.class, () -> run("prod"));

    assertEquals("운영 SSM 설정을 다른 설정 출처가 덮었다: app.keys.verify-code", error.getMessage());
    assertFalse(error.getMessage().contains("different-ssm-verify-code"));
  }

  @Test
  void 명령행_시스템속성_환경변수가_SSM_값을_덮으면_prod는_시작하지_않는다() {
    SsmTestParameterSource.setParameters(requiredParameters());
    SsmConfigException argError =
        assertThrows(
            SsmConfigException.class,
            () ->
                run(
                    new StandardEnvironment(),
                    new String[] {"--app.origin=https://evil.example"},
                    "prod"));
    assertEquals("운영 SSM 설정을 다른 설정 출처가 덮었다: app.origin", argError.getMessage());
    assertFalse(argError.getMessage().contains("evil"));
    // OS 환경변수는 relaxed 이름(APP_KEYS_LINKTOKEN)으로도 같은 키를 덮는다
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("APP_KEYS_LINKTOKEN", "env-link-token-which-is-long-enough-000")));
    SsmConfigException envError =
        assertThrows(SsmConfigException.class, () -> run(environment, new String[0], "prod"));
    assertEquals("운영 SSM 설정을 다른 설정 출처가 덮었다: app.keys.link-token", envError.getMessage());
    System.setProperty("photo.s3.bucket", "evil-bucket");
    try {
      SsmConfigException sysError = assertThrows(SsmConfigException.class, () -> run("prod"));
      assertEquals("운영 SSM 설정을 다른 설정 출처가 덮었다: photo.s3.bucket", sysError.getMessage());
    } finally {
      System.clearProperty("photo.s3.bucket");
    }
  }

  @Test
  void 덮어도_값이_같으면_시작한다() {
    SsmTestParameterSource.setParameters(requiredParameters());
    try (ConfigurableApplicationContext context =
        run(
            new StandardEnvironment(),
            new String[] {"--app.origin=https://homestamp.example"},
            "prod")) {
      assertEquals("https://homestamp.example", context.getEnvironment().getProperty("app.origin"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--spring.datasource.hikari.jdbc-url=jdbc:postgresql://evil/db",
        "--spring.datasource.hikari.data-source-properties.sslfactory=org.postgresql.ssl.NonValidatingFactory",
        "--spring.datasource.hikari.username=evil",
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "--spring.flyway.url=jdbc:postgresql://evil/db",
        "--spring.flyway.jdbc-properties.sslmode=disable"
      })
  void SSM_밖의_DB_연결_설정이_있으면_prod는_시작하지_않는다(String argument) {
    SsmTestParameterSource.setParameters(requiredParameters());

    SsmConfigException error =
        assertThrows(
            SsmConfigException.class,
            () -> run(new StandardEnvironment(), new String[] {argument}, "prod"));

    assertTrue(
        error.getMessage().startsWith("운영에서는 SSM 밖의 DB 연결 설정을 받지 않는다: "), error.getMessage());
    assertFalse(error.getMessage().contains("evil"));
    assertFalse(error.getMessage().contains("NonValidatingFactory"));
  }

  @Test
  void SSM_import를_건너뛴_prod는_시작하지_않는다() {
    SsmTestParameterSource.setParameters(requiredParameters());
    SpringApplication application = new SpringApplication(ConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles("prod");

    SsmConfigException error =
        assertThrows(
            SsmConfigException.class,
            () -> application.run("--spring.config.location=optional:classpath:/no-such-dir/"));

    assertEquals("prod 프로필인데 SSM 앱 설정을 불러오지 않았다", error.getMessage());
    assertEquals(0, SsmTestParameterSource.calls());
  }

  private static ConfigurableApplicationContext run(
      ConfigurableEnvironment environment, String[] args, String... profiles) {
    SpringApplication application = new SpringApplication(ConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles(profiles);
    application.setEnvironment(environment);
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    return application.run(args);
  }

  private static List<SsmConfigParameter> with(
      List<SsmConfigParameter> base, String key, String value) {
    List<SsmConfigParameter> parameters = new ArrayList<>(base);
    parameters.replaceAll(parameter ->
        parameter.name().equals(PREFIX + key)
            ? new SsmConfigParameter(parameter.name(), value, parameter.type())
            : parameter);
    return parameters;
  }

  private static ConfigurableApplicationContext runWithMainConfigOnly(String... profiles) {
    SpringApplication application = new SpringApplication(ConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles(profiles);
    application.setDefaultProperties(
        Map.of(
            "spring.main.banner-mode", "off",
            "spring.config.location", "classpath:/application.yml"));
    return application.run();
  }

  private static ConfigurableApplicationContext run(String... profiles) {
    SpringApplication application = new SpringApplication(ConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles(profiles);
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    return application.run();
  }

  private static List<SsmConfigParameter> requiredParameters() {
    return new ArrayList<>(SsmTestParameterSource.validParameters());
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

  private static String[] ssmPropertyNames(ConfigurableApplicationContext context) {
    return ((EnumerablePropertySource<?>)
            context.getEnvironment().getPropertySources().get("homestampSsmApp"))
        .getPropertyNames();
  }
}
