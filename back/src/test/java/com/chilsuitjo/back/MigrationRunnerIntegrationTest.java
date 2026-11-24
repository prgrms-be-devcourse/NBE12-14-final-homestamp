package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import com.chilsuitjo.back.global.scheduler.SchedulingConfig;
import com.chilsuitjo.back.notification.repository.FakeSmsGateway;
import com.chilsuitjo.back.notification.repository.SmsGateway;
import com.chilsuitjo.back.notification.service.FakeRecipientDirectory;
import com.chilsuitjo.back.notification.service.OutboxPoller;
import com.chilsuitjo.back.notification.service.RecipientDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MigrationRunnerIntegrationTest {

  private static final String OWNER = "migration_owner_test";
  private static final String MIGRATION_LOGIN = "migration_login_test";
  private static final String APP_LOGIN = "application_login_test";
  private static final String ROLE_FAILURE_LOGIN = "role_failure_login_test";
  private static final String TEST_PASSWORD = "fake-migration-password-for-tests";
  private static final String MAIN_DATABASE = "homestamp_migration_test";
  private static final String JAR_FLAG_DATABASE = "homestamp_migration_jar_flag_test";
  private static final String JAR_PROFILE_DATABASE = "homestamp_migration_jar_profile_test";
  private static final String JAR_LEGACY_PROFILE_DATABASE = "homestamp_migration_jar_legacy_test";
  private static final String ROLE_FAILURE_DATABASE = "homestamp_role_failure_test";
  private static final String FLYWAY_FAILURE_DATABASE = "homestamp_flyway_failure_test";
  private static final String PROD_PROFILE_DATABASE = "homestamp_prod_profile_test";
  private static final String LOCAL_PROFILE_DATABASE = "homestamp_local_profile_test";
  private static final String TEST_PROFILE_DATABASE = "homestamp_test_profile_test";
  private static final String PROD_TEST_PROFILE_DATABASE = "homestamp_prod_test_profile_test";
  private static final String TEST_PROD_PROFILE_DATABASE = "homestamp_test_prod_profile_test";

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  @BeforeAll
  static void prepareRolesAndDatabases() throws SQLException {
    POSTGRES.start();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE " + OWNER + " NOLOGIN");
      statement.execute(
          "CREATE ROLE " + MIGRATION_LOGIN + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
      statement.execute("CREATE ROLE " + APP_LOGIN + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
      statement.execute(
          "CREATE ROLE " + ROLE_FAILURE_LOGIN + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
      statement.execute("GRANT " + OWNER + " TO " + MIGRATION_LOGIN);
      statement.execute("CREATE DATABASE " + MAIN_DATABASE);
      statement.execute("CREATE DATABASE " + JAR_FLAG_DATABASE);
      statement.execute("CREATE DATABASE " + JAR_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + JAR_LEGACY_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + ROLE_FAILURE_DATABASE);
      statement.execute("CREATE DATABASE " + FLYWAY_FAILURE_DATABASE);
      statement.execute("CREATE DATABASE " + PROD_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + LOCAL_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + TEST_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + PROD_TEST_PROFILE_DATABASE);
      statement.execute("CREATE DATABASE " + TEST_PROD_PROFILE_DATABASE);
    }
    for (String database :
        List.of(
            MAIN_DATABASE,
            JAR_FLAG_DATABASE,
            JAR_PROFILE_DATABASE,
            JAR_LEGACY_PROFILE_DATABASE,
            ROLE_FAILURE_DATABASE,
            FLYWAY_FAILURE_DATABASE,
            PROD_PROFILE_DATABASE,
            LOCAL_PROFILE_DATABASE,
            TEST_PROFILE_DATABASE,
            PROD_TEST_PROFILE_DATABASE,
            TEST_PROD_PROFILE_DATABASE)) {
      try (Connection connection = adminConnection(database);
          Statement statement = connection.createStatement()) {
        statement.execute("GRANT CREATE ON DATABASE " + database + " TO " + OWNER);
        statement.execute("GRANT CREATE ON SCHEMA public TO " + OWNER);
        statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + MIGRATION_LOGIN);
        statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + APP_LOGIN);
        statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + ROLE_FAILURE_LOGIN);
      }
    }
    try (Connection connection = adminConnection(FLYWAY_FAILURE_DATABASE);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      statement.execute("CREATE SCHEMA record AUTHORIZATION " + OWNER);
      statement.execute("CREATE TABLE record.supplement (existing_marker integer)");
      statement.execute("RESET ROLE");
    }
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
    SsmTestParameterSource.reset();
  }

  @Test
  void migratesAllCurrentVersionsAsOwnerWithoutStartingApplicationBeans() throws SQLException {
    String[] args = migrationArguments(MAIN_DATABASE, MIGRATION_LOGIN);

    try (ConfigurableApplicationContext context = MigrationMode.start(args)) {
      assertThat(context.getBeansOfType(Flyway.class)).hasSize(1);
      assertThat(context.getBeansOfType(WebServerFactory.class)).isEmpty();
      assertThat(context.getBeansOfType(OutboxPoller.class)).isEmpty();
      assertThat(context.getBeansOfType(SchedulingConfig.class)).isEmpty();
      assertThat(context.getBeansOfType(SmsGateway.class)).isEmpty();
      assertThat(context.getBeansOfType(RecipientDirectory.class)).isEmpty();
      assertThat(context.getBeansOfType(FakeSmsGateway.class)).isEmpty();
      assertThat(context.getBeansOfType(FakeRecipientDirectory.class)).isEmpty();
      assertThat(context.getBeanNamesForAnnotation(Controller.class)).isEmpty();
      assertThat(context.getBeanNamesForAnnotation(Service.class)).isEmpty();
      assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("migrate");
      assertThat(context.getEnvironment().containsProperty("app.outbox.worker.enabled")).isFalse();
      assertThat(context.getEnvironment().containsProperty("springdoc.swagger-ui.enabled"))
          .isFalse();

      List<String> versions = appliedVersions(MAIN_DATABASE);
      assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8");
    }

    BackApplication.main(args);

    assertOwnerLoginAndObjectOwnership(MAIN_DATABASE);
  }

  @Test
  void missingOwnerMembershipFailsAndDoesNotFallbackToTheRegularApplication() {
    int exitCode =
        MigrationMode.execute(migrationArguments(ROLE_FAILURE_DATABASE, ROLE_FAILURE_LOGIN));

    assertThat(exitCode).isNotZero();
    assertThat(tableExists(ROLE_FAILURE_DATABASE, "public", "flyway_schema_history")).isFalse();
  }

  @Test
  void flywayFailureReturnsANonzeroProcessResult() throws SQLException {
    int exitCode =
        MigrationMode.execute(migrationArguments(FLYWAY_FAILURE_DATABASE, MIGRATION_LOGIN));

    assertThat(exitCode).isNotZero();
    List<String> versions = appliedVersions(FLYWAY_FAILURE_DATABASE);
    assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7");
    assertThat(versions).doesNotContain("8");
  }

  @Test
  void unavailableDatabaseReturnsANonzeroProcessResult() {
    SsmTestParameterSource.setMigrationParameters(
        SsmTestParameterSource.migrationParameters(
            "jdbc:postgresql://127.0.0.1:1/not_available?connectTimeout=1",
            MIGRATION_LOGIN,
            TEST_PASSWORD,
            OWNER));
    String[] args = {"--migrate"};

    assertThat(MigrationMode.execute(args)).isNotZero();
  }

  @Test
  void executableJarUsesBothMigrationSelectorsAndExitsZero() throws Exception {
    ProcessResult argumentResult =
        runExecutableJar(JAR_FLAG_DATABASE, MIGRATION_LOGIN, null, "--migrate");
    assertThat(argumentResult.exited()).isTrue();
    assertThat(argumentResult.exitCode())
        .as("Migration JAR output: %s", redactedOutput(argumentResult.output()))
        .isZero();
    assertThat(argumentResult.output().contains(TEST_PASSWORD)).isFalse();
    assertThat(appliedVersions(JAR_FLAG_DATABASE)).contains("1", "2", "3", "4", "5", "6", "7", "8");
    assertOwnerLoginAndObjectOwnership(JAR_FLAG_DATABASE);

    ProcessResult profileResult =
        runExecutableJar(JAR_PROFILE_DATABASE, MIGRATION_LOGIN, "migrate");
    assertThat(profileResult.exited()).isTrue();
    assertThat(profileResult.exitCode()).isZero();
    assertThat(profileResult.output().contains(TEST_PASSWORD)).isFalse();
    assertThat(appliedVersions(JAR_PROFILE_DATABASE))
        .contains("1", "2", "3", "4", "5", "6", "7", "8");
    assertOwnerLoginAndObjectOwnership(JAR_PROFILE_DATABASE);

    ProcessResult legacyProfileResult =
        runExecutableJar(JAR_LEGACY_PROFILE_DATABASE, MIGRATION_LOGIN, "migration");
    assertThat(legacyProfileResult.exited()).isTrue();
    assertThat(legacyProfileResult.exitCode()).isZero();
    assertThat(legacyProfileResult.output().contains(TEST_PASSWORD)).isFalse();
    assertThat(appliedVersions(JAR_LEGACY_PROFILE_DATABASE))
        .contains("1", "2", "3", "4", "5", "6", "7", "8");
    assertOwnerLoginAndObjectOwnership(JAR_LEGACY_PROFILE_DATABASE);
  }

  @Test
  void executableJarReturnsNonzeroForOwnerFailureAndRejectsMixedProfiles() throws Exception {
    ProcessResult ownerFailure =
        runExecutableJar(ROLE_FAILURE_DATABASE, ROLE_FAILURE_LOGIN, "migrate");
    assertThat(ownerFailure.exited()).isTrue();
    assertThat(ownerFailure.exitCode()).isNotZero();
    assertThat(ownerFailure.output().contains(TEST_PASSWORD)).isFalse();
    assertThat(tableExists(ROLE_FAILURE_DATABASE, "public", "flyway_schema_history")).isFalse();

    ProcessResult mixedProfiles =
        runExecutableJar(ROLE_FAILURE_DATABASE, ROLE_FAILURE_LOGIN, "prod,migrate");
    assertThat(mixedProfiles.exited()).isTrue();
    assertThat(mixedProfiles.exitCode()).isNotZero();
    assertThat(mixedProfiles.output().contains(TEST_PASSWORD)).isFalse();

    ProcessResult mixedLegacyProfiles =
        runExecutableJar(ROLE_FAILURE_DATABASE, ROLE_FAILURE_LOGIN, "prod,migration");
    assertThat(mixedLegacyProfiles.exited()).isTrue();
    assertThat(mixedLegacyProfiles.exitCode()).isNotZero();
    assertThat(mixedLegacyProfiles.output().contains(TEST_PASSWORD)).isFalse();
  }

  @Test
  void productionJarDoesNotContainTheTestOnlySsmFixture() throws Exception {
    String jarPath = System.getProperty("migration.test.production-boot-jar");
    assertThat(jarPath).isNotBlank();

    try (JarFile jar = new JarFile(jarPath)) {
      assertThat(
              jar.getEntry(
                  "BOOT-INF/classes/com/chilsuitjo/back/global/config/ssm/SsmTestParameterSource.class"))
          .isNull();
      assertThat(
              jar.getEntry(
                  "BOOT-INF/classes/com/chilsuitjo/back/global/config/ssm/SsmTestBootstrapRegistryInitializer.class"))
          .isNull();
    }
  }

  @Test
  void appLoginCannotSetOwnerRole() throws SQLException {
    try (Connection connection = connection(MAIN_DATABASE, APP_LOGIN);
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("SET ROLE " + OWNER))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void prodDisablesFlywayForBothProfileOrdersWithoutChangingTheDatabase() throws SQLException {
    assertFlywayProfile(PROD_PROFILE_DATABASE, "prod", false);
    assertFlywayProfile(PROD_TEST_PROFILE_DATABASE, "prod,test", false);
    assertFlywayProfile(TEST_PROD_PROFILE_DATABASE, "test,prod", false);

    assertThat(tableExists(PROD_PROFILE_DATABASE, "public", "flyway_schema_history")).isFalse();
    assertThat(tableExists(PROD_TEST_PROFILE_DATABASE, "public", "flyway_schema_history"))
        .isFalse();
    assertThat(tableExists(TEST_PROD_PROFILE_DATABASE, "public", "flyway_schema_history"))
        .isFalse();
  }

  @Test
  void localAndTestKeepFlywayEnabledAndApplyTheMigrations() throws SQLException {
    assertFlywayProfile(LOCAL_PROFILE_DATABASE, "local", true);
    assertFlywayProfile(TEST_PROFILE_DATABASE, "test", true);

    assertThat(appliedVersionsAsAdmin(LOCAL_PROFILE_DATABASE))
        .contains("1", "2", "3", "4", "5", "6", "7", "8");
    assertThat(appliedVersionsAsAdmin(TEST_PROFILE_DATABASE))
        .contains("1", "2", "3", "4", "5", "6", "7", "8");
  }

  private static String[] migrationArguments(String database, String login) {
    SsmTestParameterSource.setMigrationParameters(
        SsmTestParameterSource.migrationParameters(jdbcUrl(database), login, TEST_PASSWORD, OWNER));
    return new String[] {
      "--migrate",
      "--spring.config.location=classpath:/application.yml",
      "--spring.config.import=homestamp-ssm:",
      "--spring.flyway.enabled=false",
      "--spring.flyway.locations=classpath:db/migration-failure"
    };
  }

  private static String jdbcUrl(String database) {
    return POSTGRES.getJdbcUrl().replace(POSTGRES.getDatabaseName(), database);
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection connection(String database, String login) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(database), login, TEST_PASSWORD);
  }

  private static List<String> appliedVersions(String database) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Connection connection = connection(database, MIGRATION_LOGIN);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank")) {
      while (result.next()) {
        versions.add(result.getString(1));
      }
    }
    return versions;
  }

  private static List<String> appliedVersionsAsAdmin(String database) throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Connection connection = adminConnection(database);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank")) {
      while (result.next()) {
        versions.add(result.getString(1));
      }
    }
    return versions;
  }

  private static void assertOwnerLoginAndObjectOwnership(String database) throws SQLException {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT rolcanlogin FROM pg_roles WHERE rolname = '" + OWNER + "'")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getBoolean(1)).isFalse();
    }

    try (Connection connection = connection(database, MIGRATION_LOGIN);
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("SELECT current_user")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(MIGRATION_LOGIN);
      }
      statement.execute("SET ROLE " + OWNER);
      try (ResultSet ownerResult = statement.executeQuery("SELECT current_user")) {
        assertThat(ownerResult.next()).isTrue();
        assertThat(ownerResult.getString(1)).isEqualTo(OWNER);
      }
    }

    try (Connection connection = adminConnection(database);
        Statement statement = connection.createStatement()) {
      assertRoleOwnsObjects(statement, "record");
      assertRoleOwnsObjects(statement, "state");
      assertRoleOwnsObjects(statement, "log");
      try (ResultSet result =
          statement.executeQuery(
              "SELECT pg_catalog.pg_get_userbyid(c.relowner) "
                  + "FROM pg_catalog.pg_class c "
                  + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                  + "WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(OWNER);
      }
    }
  }

  private static void assertRoleOwnsObjects(Statement statement, String schema)
      throws SQLException {
    try (ResultSet result =
        statement.executeQuery(
            "SELECT count(*) FILTER (WHERE pg_catalog.pg_get_userbyid(c.relowner) <> '"
                + OWNER
                + "'), count(*) FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = '"
                + schema
                + "' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getLong(1)).isZero();
      assertThat(result.getLong(2)).isPositive();
    }
  }

  private static boolean tableExists(String database, String schema, String table) {
    try (Connection target = adminConnection(database);
        Statement statement = target.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT to_regclass('" + schema + "." + table + "') IS NOT NULL")) {
      result.next();
      return result.getBoolean(1);
    } catch (SQLException exception) {
      return false;
    }
  }

  private static Connection adminConnection(String database) throws SQLException {
    return DriverManager.getConnection(
        jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static ProcessResult runExecutableJar(
      String database, String login, String activeProfiles, String... arguments) throws Exception {
    String jarPath = System.getProperty("migration.test.boot-jar");
    assertThat(jarPath).isNotBlank();
    assertThat(Path.of(jarPath)).exists();

    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jarPath));
    command.add("--spring.config.location=classpath:/application.yml");
    command.add("--spring.config.import=homestamp-ssm:");
    command.add("--spring.flyway.enabled=false");
    command.addAll(List.of(arguments));
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> childEnvironment = processBuilder.environment();
    childEnvironment.clear();
    childEnvironment.put("HOMESTAMP_TEST_MIGRATION_SSM", "true");
    childEnvironment.put("HOMESTAMP_TEST_MIGRATION_DB_URL", jdbcUrl(database));
    childEnvironment.put("HOMESTAMP_TEST_MIGRATION_DB_USERNAME", login);
    childEnvironment.put("HOMESTAMP_TEST_MIGRATION_DB_PASSWORD", TEST_PASSWORD);
    childEnvironment.put("HOMESTAMP_TEST_MIGRATION_OWNER_ROLE", OWNER);
    if (activeProfiles != null) {
      childEnvironment.put("SPRING_PROFILES_ACTIVE", activeProfiles);
    }
    Process process = processBuilder.start();
    Process runningProcess = process;
    CompletableFuture<String> outputFuture =
        CompletableFuture.supplyAsync(() -> {
          try {
            return new String(
                runningProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException exception) {
            throw new IllegalStateException("Could not read migration process output.");
          }
        });

    boolean exited = process.waitFor(40, TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    String output = outputFuture.get(5, TimeUnit.SECONDS);
    return new ProcessResult(exited, exited ? process.exitValue() : -1, output);
  }

  private static void assertFlywayProfile(String database, String profiles, boolean enabled) {
    SsmTestParameterSource.setParameters(
        appParameters(jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()));
    SpringApplication application = new SpringApplication(FlywayOnlyConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setAdditionalProfiles(profiles.split(","));
    application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
    String[] arguments =
        profiles.contains("prod")
            ? new String[0]
            : new String[] {
              "--spring.datasource.url=" + jdbcUrl(database),
              "--spring.datasource.username=" + POSTGRES.getUsername(),
              "--spring.datasource.password=" + POSTGRES.getPassword()
            };
    try (ConfigurableApplicationContext context = application.run(arguments)) {
      assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class, true))
          .isEqualTo(enabled);
      if (enabled) {
        assertThat(context.getBeansOfType(Flyway.class)).hasSize(1);
      } else {
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
      }
    }
  }

  private static List<SsmConfigParameter> appParameters(
      String url, String username, String password) {
    String prefix = "/homestamp/prod/app/";
    return List.of(
        new SsmConfigParameter(prefix + "spring.datasource.url", url, "String"),
        new SsmConfigParameter(prefix + "spring.datasource.username", username, "SecureString"),
        new SsmConfigParameter(prefix + "spring.datasource.password", password, "SecureString"),
        new SsmConfigParameter(prefix + "app.keys.verify-code", "fake-verify-code", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.verify-ticket", "fake-verify-ticket", "SecureString"),
        new SsmConfigParameter(prefix + "app.keys.phone-hmac", "fake-phone-hmac", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.access-token", "fake-access-token-long-enough-0001", "SecureString"),
        new SsmConfigParameter(
            prefix + "app.keys.refresh-token",
            "fake-refresh-token-long-enough-0002",
            "SecureString"),
        new SsmConfigParameter(prefix + "app.origin", "https://homestamp.example", "String"));
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  static class FlywayOnlyConfiguration {}

  private record ProcessResult(boolean exited, int exitCode, String output) {}

  private static String redactedOutput(String output) {
    return output.replace(TEST_PASSWORD, "[REDACTED]");
  }
}
