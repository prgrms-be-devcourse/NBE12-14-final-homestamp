package com.chilsuitjo.back.global.config.ssm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationSsmParameterMapperTest {

  private static final String PREFIX = "/homestamp/prod/migration/";
  private static final String TEST_PASSWORD = "fake-migration-password-for-tests";

  @Test
  void mapsOnlyTheFourRequiredMigrationSettings() {
    Map<String, Object> properties = MigrationSsmParameterMapper.toProperties(validParameters());

    assertThat(properties)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "spring.datasource.url", "jdbc:postgresql://db.example.test:5432/homestamp",
                "spring.datasource.username", "migration_login_test",
                "spring.datasource.password", TEST_PASSWORD,
                "app.migration.owner-role", "homestamp_owner"));
  }

  @Test
  void rejectsMissingRequiredParameters() {
    List<SsmConfigParameter> parameters = new ArrayList<>(validParameters());
    parameters.removeIf(
        parameter -> parameter.name().equals(PREFIX + "spring.datasource.password"));

    assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.password")
        .hasMessageNotContaining(TEST_PASSWORD);
  }

  @Test
  void rejectsEmptyOrWhitespaceValuesWithoutEchoingThem() {
    for (String value : List.of("", "   ")) {
      List<SsmConfigParameter> parameters = validParameters();
      parameters.set(
          2, new SsmConfigParameter(PREFIX + "spring.datasource.password", value, "SecureString"));

      assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(TEST_PASSWORD);
    }
  }

  @Test
  void rejectsDuplicateParameterNames() {
    List<SsmConfigParameter> parameters = new ArrayList<>(validParameters());
    parameters.add(validParameters().getFirst());

    assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.url");
  }

  @Test
  void rejectsParameterTypesThatDoNotMatchTheContract() {
    List<SsmConfigParameter> parameters = validParameters();
    parameters.set(
        1,
        new SsmConfigParameter(
            PREFIX + "spring.datasource.username", "migration_login_test", "String"));

    assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.username");
  }

  @Test
  void rejectsAppAndPurgePaths() {
    for (String name :
        List.of(
            "/homestamp/prod/app/spring.datasource.url",
            "/homestamp/prod/purge/spring.datasource.url")) {
      List<SsmConfigParameter> parameters = validParameters();
      parameters.set(
          0,
          new SsmConfigParameter(
              name, "jdbc:postgresql://db.example.test:5432/homestamp", "String"));

      assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(TEST_PASSWORD);
    }
  }

  @Test
  void rejectsUnknownAndNestedParameterNames() {
    for (String suffix : List.of("spring.profiles.active", "nested/spring.datasource.url")) {
      List<SsmConfigParameter> parameters = validParameters();
      parameters.set(
          0,
          new SsmConfigParameter(
              PREFIX + suffix, "jdbc:postgresql://db.example.test:5432/homestamp", "String"));

      assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(TEST_PASSWORD);
    }
  }

  @Test
  void rejectsInvalidOwnerRoleIdentifiers() {
    for (String ownerRole : List.of("1owner", "owner.role", "owner;RESET ROLE", "owner role")) {
      List<SsmConfigParameter> parameters = validParameters();
      parameters.set(
          3, new SsmConfigParameter(PREFIX + "app.migration.owner-role", ownerRole, "String"));

      assertThatThrownBy(() -> MigrationSsmParameterMapper.toProperties(parameters))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.migration.owner-role")
          .hasMessageNotContaining(TEST_PASSWORD);
    }
  }

  @Test
  void parameterTextRepresentationRedactsItsValue() {
    SsmConfigParameter parameter = validParameters().get(2);

    assertThat(parameter.toString()).contains("<redacted>").doesNotContain(TEST_PASSWORD);
  }

  private static List<SsmConfigParameter> validParameters() {
    return new ArrayList<>(
        List.of(
            new SsmConfigParameter(
                PREFIX + "spring.datasource.url",
                "jdbc:postgresql://db.example.test:5432/homestamp",
                "String"),
            new SsmConfigParameter(
                PREFIX + "spring.datasource.username", "migration_login_test", "SecureString"),
            new SsmConfigParameter(
                PREFIX + "spring.datasource.password", TEST_PASSWORD, "SecureString"),
            new SsmConfigParameter(
                PREFIX + "app.migration.owner-role", "homestamp_owner", "String")));
  }
}
