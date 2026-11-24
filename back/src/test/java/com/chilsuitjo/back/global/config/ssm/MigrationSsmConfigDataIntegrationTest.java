package com.chilsuitjo.back.global.config.ssm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

class MigrationSsmConfigDataIntegrationTest {

  private static final String PASSWORD = "fake-migration-password-for-tests";

  @AfterEach
  void resetSource() {
    SsmTestParameterSource.reset();
  }

  @Test
  void migrationConfigDataSuppliesSettingsBeforeDataSourceBeanCreation() {
    SsmTestParameterSource.setMigrationParameters(
        SsmTestParameterSource.migrationParameters(
            "jdbc:postgresql://db.example.test:5432/homestamp",
            "migration_login_test",
            PASSWORD,
            "homestamp_owner"));

    try (ConfigurableApplicationContext context = runMigrationConfig()) {
      DriverManagerDataSource dataSource = context.getBean(DriverManagerDataSource.class);
      assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("migrate");
      assertThat(dataSource.getUrl()).isEqualTo("jdbc:postgresql://db.example.test:5432/homestamp");
      assertThat(dataSource.getUsername()).isEqualTo("migration_login_test");
      assertThat(dataSource.getPassword()).isEqualTo(PASSWORD);
      assertThat(context.getEnvironment().getProperty("app.migration.owner-role"))
          .isEqualTo("homestamp_owner");
      assertThat(context.getEnvironment().getProperty("app.keys.access-token")).isBlank();
      var migrationProperties =
          context.getEnvironment().getPropertySources().get("homestampSsmMigration");
      assertThat(((EnumerablePropertySource<?>) migrationProperties).getPropertyNames())
          .containsExactlyInAnyOrder(
              "spring.datasource.url",
              "spring.datasource.username",
              "spring.datasource.password",
              "app.migration.owner-role");
      assertThat(migrationProperties.containsProperty("app.keys.access-token")).isFalse();
      assertThat(migrationProperties.toString()).doesNotContain(PASSWORD);
      assertThat(SsmTestParameterSource.migrationCalls()).isEqualTo(1);
      assertThat(SsmTestParameterSource.calls()).isZero();
    }
  }

  @Test
  void missingSettingsFailDuringConfigDataLoadingWithoutSecretDisclosure() {
    SsmTestParameterSource.setMigrationParameters(List.of());

    assertThatThrownBy(MigrationSsmConfigDataIntegrationTest::runMigrationConfig)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("필수 Migration SSM 설정이 없다")
        .hasMessageNotContaining(PASSWORD);
    assertThat(SsmTestParameterSource.migrationCalls()).isEqualTo(1);
    assertThat(SsmTestParameterSource.calls()).isZero();
  }

  @Test
  void accessDeniedAndKmsFailureAreSanitizedAndFailClosed() {
    for (String failure : List.of("AccessDenied fake-secret", "KMS Decrypt denied fake-secret")) {
      SsmTestParameterSource.setMigrationFailure(new IllegalStateException(failure));

      assertThatThrownBy(MigrationSsmConfigDataIntegrationTest::runMigrationConfig)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("운영 SSM 설정을 불러오거나 검증하지 못했다")
          .hasMessageNotContaining("fake-secret");
      assertThat(SsmTestParameterSource.migrationCalls()).isEqualTo(1);
      SsmTestParameterSource.reset();
    }
    assertThat(SsmTestParameterSource.calls()).isZero();
  }

  @Test
  void timeoutFailsClosedWithoutEchoingTheUnderlyingMessage() {
    SsmTestParameterSource.setMigrationFailure(
        ApiCallTimeoutException.create("timeout with fake-secret", null));

    assertThatThrownBy(MigrationSsmConfigDataIntegrationTest::runMigrationConfig)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("운영 Migration SSM 설정 조회 시간이 초과됐다")
        .hasMessageNotContaining("fake-secret");
    assertThat(SsmTestParameterSource.migrationCalls()).isEqualTo(1);
    assertThat(SsmTestParameterSource.calls()).isZero();
  }

  private static ConfigurableApplicationContext runMigrationConfig() {
    SpringApplication application = new SpringApplication(MigrationConfigProbe.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    return application.run(
        "--spring.profiles.active=migrate", "--spring.config.import=homestamp-ssm:migration");
  }

  @Configuration(proxyBeanMethods = false)
  static class MigrationConfigProbe {

    @Bean
    DriverManagerDataSource dataSource(org.springframework.core.env.Environment environment) {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
      dataSource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
      dataSource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
      return dataSource;
    }
  }
}
