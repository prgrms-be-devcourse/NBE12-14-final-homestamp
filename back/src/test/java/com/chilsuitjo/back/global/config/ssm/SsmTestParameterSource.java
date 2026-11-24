package com.chilsuitjo.back.global.config.ssm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory source for Spring context tests; it never creates an AWS client. */
public final class SsmTestParameterSource implements SsmParameterSource {

  private static volatile List<SsmConfigParameter> parameters = List.of();
  private static volatile List<SsmConfigParameter> migrationParameters = List.of();
  private static final AtomicInteger calls = new AtomicInteger();
  private static final AtomicInteger migrationCalls = new AtomicInteger();
  private static volatile RuntimeException failure;
  private static volatile RuntimeException migrationFailure;

  public static void setParameters(List<SsmConfigParameter> newParameters) {
    parameters = List.copyOf(newParameters);
    failure = null;
  }

  public static void setFailure(RuntimeException newFailure) {
    failure = newFailure;
  }

  public static void setMigrationParameters(List<SsmConfigParameter> newParameters) {
    migrationParameters = List.copyOf(newParameters);
    migrationFailure = null;
  }

  public static void setMigrationFailure(RuntimeException newFailure) {
    migrationFailure = newFailure;
  }

  public static void reset() {
    parameters = List.of();
    migrationParameters = List.of();
    failure = null;
    migrationFailure = null;
    calls.set(0);
    migrationCalls.set(0);
  }

  public static int calls() {
    return calls.get();
  }

  public static int migrationCalls() {
    return migrationCalls.get();
  }

  public static List<SsmConfigParameter> migrationParameters(
      String url, String username, String password, String ownerRole) {
    String prefix = MigrationSsmParameterMapper.PARAMETER_PREFIX;
    return List.of(
        new SsmConfigParameter(prefix + "spring.datasource.url", url, "String"),
        new SsmConfigParameter(prefix + "spring.datasource.username", username, "SecureString"),
        new SsmConfigParameter(prefix + "spring.datasource.password", password, "SecureString"),
        new SsmConfigParameter(prefix + "app.migration.owner-role", ownerRole, "String"));
  }

  public static List<SsmConfigParameter> validParameters() {
    String prefix = SsmAppParameterMapper.PARAMETER_PREFIX;
    return List.of(
        new SsmConfigParameter(
            prefix + "spring.datasource.url",
            "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full",
            "String"),
        new SsmConfigParameter(prefix + "spring.datasource.username", "app-user", "SecureString"),
        new SsmConfigParameter(
            prefix + "spring.datasource.password", "db-password-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-code", "verify-code-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-ticket", "verify-ticket-secret", "SecureString"),
        new SsmConfigParameter(prefix + "app.keys.phone-hmac", "phone-hmac-secret", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.access-token",
            "access-token-secret-which-is-long-enough-0001",
            "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.refresh-token",
            "refresh-token-secret-which-is-long-enough-0002",
            "SecureString"),
        new SsmConfigParameter(prefix + "app.origin", "https://homestamp.example", "String"));
  }

  @Override
  public List<SsmConfigParameter> loadAppParameters() {
    calls.incrementAndGet();
    if (failure != null) {
      throw failure;
    }
    return new ArrayList<>(parameters);
  }

  @Override
  public List<SsmConfigParameter> loadMigrationParameters() {
    migrationCalls.incrementAndGet();
    if (migrationFailure != null) {
      throw migrationFailure;
    }
    if (!migrationParameters.isEmpty()) {
      return new ArrayList<>(migrationParameters);
    }
    if (Boolean.parseBoolean(System.getenv("HOMESTAMP_TEST_MIGRATION_SSM"))) {
      return migrationParameters(
          System.getenv("HOMESTAMP_TEST_MIGRATION_DB_URL"),
          System.getenv("HOMESTAMP_TEST_MIGRATION_DB_USERNAME"),
          System.getenv("HOMESTAMP_TEST_MIGRATION_DB_PASSWORD"),
          System.getenv("HOMESTAMP_TEST_MIGRATION_OWNER_ROLE"));
    }
    return List.of();
  }
}
