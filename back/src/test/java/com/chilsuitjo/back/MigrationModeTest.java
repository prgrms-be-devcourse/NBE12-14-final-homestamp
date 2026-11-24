package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MigrationModeTest {

  @AfterEach
  void resetMigrationSsmFixture() {
    SsmTestParameterSource.reset();
  }

  @Test
  void migrationModeRequiresTheExplicitArgument() {
    assertThat(MigrationMode.isRequested(new String[] {"--migrate"})).isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active=migrate"}))
        .isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active=migration"}))
        .isTrue();
    assertThat(MigrationMode.isRequested(new String[] {"--spring.profiles.active=prod"})).isFalse();
  }

  @Test
  void migrationModeRejectsProductionAndApplicationProfileCombinations() {
    for (String profile : new String[] {"prod,migrate", "prod,migration", "test,migrate"}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() ->
              MigrationMode.start(
                  new String[] {"--migrate", "--spring.profiles.active=" + profile}))
          .withMessageContaining("accepts only the migrate profile")
          .withMessageNotContaining("password");
    }
  }

  @Test
  void migrationModeRequiresAnOwnerRoleConfiguration() {
    SsmTestParameterSource.setMigrationParameters(
        List.of(
            new SsmConfigParameter(
                "/homestamp/prod/migration/spring.datasource.url",
                "jdbc:postgresql://127.0.0.1:1/not_available?connectTimeout=1",
                "String"),
            new SsmConfigParameter(
                "/homestamp/prod/migration/spring.datasource.username",
                "migration_login_test",
                "SecureString"),
            new SsmConfigParameter(
                "/homestamp/prod/migration/spring.datasource.password",
                "fake-migration-password-for-tests",
                "SecureString")));

    assertThatThrownBy(() -> MigrationMode.start(new String[] {"--migrate"}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.migration.owner-role")
        .hasMessageNotContaining("fake-migration-password-for-tests");
  }

  @Test
  void migrationModeRejectsDataSourceCommandLineOverridesWithoutEchoingThem() {
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            MigrationMode.start(
                new String[] {"--migrate", "--spring.datasource.password=fake-password"}))
        .withMessageContaining("must come from the Migration SSM path")
        .withMessageNotContaining("fake-password");
  }

  @Test
  void migrationModeRejectsSystemPropertyOverridesWithoutEchoingThem() {
    Properties systemProperties = new Properties();
    systemProperties.setProperty("spring.datasource.password", "fake-system-password");

    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            MigrationMode.rejectMigrationSettingOverrides(
                Map.of(), systemProperties, new String[] {"--migrate"}))
        .withMessageContaining("must come from the Migration SSM path")
        .withMessageNotContaining("fake-system-password");

    systemProperties.setProperty("spring.datasource.password", "");
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            MigrationMode.rejectMigrationSettingOverrides(
                Map.of(), systemProperties, new String[] {"--migrate"}))
        .withMessageContaining("must come from the Migration SSM path");
  }

  @Test
  void migrationModeRejectsEnvironmentOverridesWithoutEchoingThem() {
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            MigrationMode.rejectMigrationSettingOverrides(
                Map.of("SPRING_DATASOURCE_PASSWORD", "fake-environment-password"),
                new Properties(),
                new String[] {"--migrate"}))
        .withMessageContaining("must come from the Migration SSM path")
        .withMessageNotContaining("fake-environment-password");

    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            MigrationMode.rejectMigrationSettingOverrides(
                Map.of("SPRING_DATASOURCE_PASSWORD", ""),
                new Properties(),
                new String[] {"--migrate"}))
        .withMessageContaining("must come from the Migration SSM path");
  }
}
