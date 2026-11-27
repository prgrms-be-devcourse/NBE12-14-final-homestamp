package com.chilsuitjo.back;

import com.chilsuitjo.back.global.config.migration.DatabaseGrants;
import com.chilsuitjo.back.global.config.migration.GrantRoles;
import com.chilsuitjo.back.global.config.migration.MigrationSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code --migrate}로 실행하는 일회성 Migration.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. {@link MigrationSettings}가 검증한 파일 값으로 Hikari·Flyway 설정 객체를 코드에서 직접 만들고,
 * 만든 객체를 다시 검사한 뒤에만 DB에 연결한다. 그래서 환경변수·시스템 속성·명령 인자·{@code SPRING_APPLICATION_JSON}·{@code
 * spring.datasource.hikari.*}·{@code spring.flyway.*}·{@code FLYWAY_*} 같은 설정 출처는 DB 접속·Flyway 계약에
 * 닿지 않는다. 명령 인자는 {@code --migrate} 하나만 받는다.
 *
 * <p>막지 못하는 것: JVM 자체에 코드를 넣는 입력({@code JAVA_TOOL_OPTIONS}·{@code -javaagent} 등)과 호스트 침해. 이는 배포
 * 스크립트가 컨테이너 인자·환경변수를 고정값으로만 넘기고 호스트 접근을 통제하는 운영 책임이다.
 *
 * <p>Flyway 계약: 위치 {@code classpath:db/migration}만, 연결마다 {@code SET ROLE "<owner>"}, 미래 버전·체크섬
 * 불일치·이미 적용된 버전의 누락은 모두 검증 실패(종료 1). {@code clean}·{@code repair}·이력 수정은 하지 않는다.
 *
 * <p>{@code --apply-grants}는 같은 파일·같은 Hikari·Flyway 구성으로 접속하지만 {@code migrate()}를 하지 않는다. {@code
 * validate()}로 DB 이력이 이 이미지의 Migration과 정확히 같은지(대기·미래·체크섬·누락 없음) 확인한 뒤에만 {@link DatabaseGrants}로
 * {@code db/grants/grants.sql}을 한 트랜잭션에서 실행한다. 대상 역할은 이미지 안의 {@link GrantRoles#RESOURCE}에서만 읽는다. 두
 * 인자는 함께 받지 않는다(배포는 {@code --migrate} 성공 뒤 별도 실행으로 {@code --apply-grants}).
 */
public final class MigrationMode {

  private static final Logger log = LoggerFactory.getLogger(MigrationMode.class);

  static final String MIGRATE_ARGUMENT = "--migrate";
  static final String APPLY_GRANTS_ARGUMENT = "--apply-grants";
  static final String MIGRATION_LOCATION = "classpath:db/migration";
  static final String DRIVER = "org.postgresql.Driver";
  static final String POOL_NAME = "homestamp-migration";
  private static final String MIGRATE_PROFILE = "migrate";
  private static final String LEGACY_MIGRATION_PROFILE = "migration";

  private MigrationMode() {}

  /** 한 번 실행에서 하는 일. 인자 하나로만 고른다. */
  enum Operation {
    MIGRATE("Migration"),
    APPLY_GRANTS("GRANT");

    final String label;

    Operation(String label) {
      this.label = label;
    }
  }

  /**
   * 실행 인자나 프로필이 Migration·GRANT를 가리키면 이 모드로 보낸다(그다음 인자 검사에서 {@code --migrate}나 {@code
   * --apply-grants} 하나만 통과한다).
   */
  public static boolean isRequested(String[] args) {
    return Arrays.asList(args).contains(MIGRATE_ARGUMENT)
        || Arrays.asList(args).contains(APPLY_GRANTS_ARGUMENT)
        || requestedProfileNames(args).stream().anyMatch(MigrationMode::isMigrationProfile);
  }

  /** Migration을 실행하고 종료 코드(성공 0, 실패 1)를 돌려준다. 실패 메시지에는 설정 값을 넣지 않는다. */
  public static int execute(String[] args) {
    return execute(args, new MigrationSettings());
  }

  static int execute(String[] args, MigrationSettings settings) {
    // Spring LoggingSystem을 거치지 않으므로 logback 기본값(DEBUG)을 INFO로 고정한다(Hikari가 DEBUG에서 접속 설정을 찍음)
    if (LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context) {
      context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.INFO);
      // Flyway는 INFO에서 "Database: <JDBC 주소>"를 찍는다(호스트·DB 이름·인증서 경로). 주소는 로그에 남기지 않는다
      context
          .getLogger("org.flywaydb.core.FlywayExecutor")
          .setLevel(ch.qos.logback.classic.Level.WARN);
    }
    String label = Arrays.asList(args).contains(APPLY_GRANTS_ARGUMENT) ? "GRANT를" : "Migration을";
    try {
      run(args, System.getProperties(), settings);
      return 0;
    } catch (RuntimeException exception) {
      log.error("{} 실행하지 못했다: {}", label, describe(exception));
      return 1;
    }
  }

  /** 인자·설정 검증 → Hikari 설정 재검사 → Flyway 설정 재검사 → (여기서 처음) 연결·migrate. */
  static void run(String[] args, Properties systemProperties, MigrationSettings settings) {
    run(args, systemProperties, settings, MIGRATION_LOCATION);
  }

  /** 위치는 운영에서 {@link #MIGRATION_LOCATION} 고정이다. 다른 위치는 위치 없음·빈 위치·이름 오류를 재현하는 테스트에서만 넘긴다. */
  static void run(
      String[] args, Properties systemProperties, MigrationSettings settings, String location) {
    run(args, systemProperties, settings, location, GrantRoles.RESOURCE);
  }

  /** 역할 설정 리소스는 운영에서 {@link GrantRoles#RESOURCE} 고정이다. 다른 이름은 형식 오류를 재현하는 테스트에서만 넘긴다. */
  static void run(
      String[] args,
      Properties systemProperties,
      MigrationSettings settings,
      String location,
      String rolesResource) {
    Prepared prepared = prepare(args, systemProperties, settings);
    // GRANT 역할 설정은 DB에 닿기 전에 읽고 검증한다
    GrantRoles roles =
        prepared.operation() == Operation.APPLY_GRANTS
            ? GrantRoles.fromResource(
                rolesResource, prepared.ownerRole(), prepared.values().username())
            : null;
    // 인자 없는 생성자는 풀을 바로 만들지 않는다. 첫 연결은 Flyway가 검사를 통과한 뒤 연다
    try (HikariDataSource dataSource = new HikariDataSource()) {
      prepared.hikari().copyStateTo(dataSource);
      verifyHikari(dataSource, prepared.values());
      Flyway flyway = flywayConfiguration(dataSource, prepared.ownerRole(), location).load();
      // 위치는 있지만 SQL Migration이 하나도 없으면(그리고 DB 이력도 비었으면) 아무것도 하지 않고 성공하므로 막는다.
      // 이력이 있는 DB는 이 경우 validate가 "로컬에 없는 적용 버전"으로 실패한다
      if (flyway.info().all().length == 0) {
        throw new IllegalStateException("적용하거나 검증할 Migration이 하나도 없다");
      }
      if (prepared.operation() == Operation.MIGRATE) {
        flyway.migrate();
        return;
      }
      applyGrants(dataSource, flyway, roles);
    }
  }

  /**
   * DB 이력 지문 → {@code validate()}(대기 중인 Migration·미래 버전·체크섬·누락이 있으면 실패) → GRANT 트랜잭션 안에서 지문을 다시
   * 확인하고 실행. 검증에 실패하면 GRANT SQL을 실행하지 않는다.
   */
  private static void applyGrants(HikariDataSource dataSource, Flyway flyway, GrantRoles roles) {
    try {
      String fingerprint;
      try (Connection connection = dataSource.getConnection()) {
        fingerprint = readFingerprint(connection, roles.owner());
      }
      flyway.validate();
      if (fingerprint == null) {
        // 지문을 읽을 때는 이력이 없었는데 검증은 통과했다: 그 사이 다른 Migration이 끼었다
        throw new IllegalStateException(
            "Flyway 검증 뒤 Migration 이력이 바뀌었다(다른 배포와 겹침). GRANT를 실행하지 않는다");
      }
      try (Connection connection = dataSource.getConnection()) {
        DatabaseGrants.apply(connection, roles, fingerprint);
        log.info("GRANT를 적용했다");
      }
    } catch (SQLException exception) {
      throw new GrantFailure(exception);
    }
  }

  private static String readFingerprint(Connection connection, String owner) throws SQLException {
    try {
      return DatabaseGrants.readHistoryFingerprint(connection, owner);
    } catch (SQLException exception) {
      // 이력 테이블이 없으면(아무것도 적용하지 않은 DB) 아래 validate가 대기 중인 Migration으로 실패한다
      if ("42P01".equals(exception.getSQLState())) {
        return null;
      }
      throw exception;
    }
  }

  /** GRANT 실행 중 SQL 오류. 로그에는 SQLState와 grants.sql이 직접 낸 고정 메시지만 남긴다. */
  static final class GrantFailure extends RuntimeException {
    final String sqlState;
    final String safeMessage;

    GrantFailure(SQLException exception) {
      super("GRANT SQL 실패", null, false, false);
      this.sqlState = exception.getSQLState();
      String message = null;
      // P0001 = grants.sql의 RAISE EXCEPTION(사전 검사). 첫 줄(서버 기본 메시지)만 쓰고 Where·SQL·상세는 버린다
      if ("P0001".equals(sqlState) && exception.getMessage() != null) {
        String first = exception.getMessage().lines().findFirst().orElse("");
        String primary = first.substring(first.indexOf(": ") + 2);
        // 허용 문자(영숫자·식별자·구두점)만이면 앞 600자까지 남긴다. 테이블·컬럼 이름 목록이 길 수 있다
        if (first.contains(": ") && primary.matches("[A-Za-z0-9 _.,:;'(){}%=<>/-]{1,4000}")) {
          message = primary.length() > 600 ? primary.substring(0, 600) + "..." : primary;
        }
      }
      this.safeMessage = message;
    }
  }

  record Prepared(Operation operation, HikariConfig hikari, MigrationSettings.Values values) {
    String ownerRole() {
      return values.ownerRole();
    }
  }

  /** DB에 연결하지 않는 단계: 인자·Hikari 설정 파일 속성 거절 → 파일 읽기·검증 → Hikari 설정 생성·재검사. */
  static Prepared prepare(String[] args, Properties systemProperties, MigrationSettings settings) {
    Operation operation = rejectArguments(args);
    rejectHikariConfigurationFile(systemProperties);
    MigrationSettings.Values values = settings.load();
    HikariConfig config = new HikariConfig();
    config.setPoolName(POOL_NAME);
    config.setDriverClassName(DRIVER);
    config.setJdbcUrl(values.url());
    config.setUsername(values.username());
    config.setPassword(values.password());
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(1);
    config.setInitializationFailTimeout(1);
    verifyHikari(config, values);
    return new Prepared(operation, config, values);
  }

  /** Flyway 설정을 코드에서만 만든다. 환경변수·설정 파일을 읽는 {@code envVars()}·{@code configuration(...)}은 부르지 않는다. */
  static FluentConfiguration flywayConfiguration(DataSource dataSource, String ownerRole) {
    return flywayConfiguration(dataSource, ownerRole, MIGRATION_LOCATION);
  }

  static FluentConfiguration flywayConfiguration(
      DataSource dataSource, String ownerRole, String location) {
    FluentConfiguration configuration =
        Flyway.configure(MigrationMode.class.getClassLoader())
            .dataSource(dataSource)
            .locations(location)
            .failOnMissingLocations(true)
            // 이름이 규칙에 맞지 않는 SQL 파일(예: V9_add.sql)을 조용히 건너뛰지 않고 실패한다
            .validateMigrationNaming(true)
            // 위치 안의 SQL 콜백(afterMigrate*.sql 등)은 실행하지 않는다. GRANT는 Flyway 콜백이 아니라 별도 --apply-grants
            // 실행이다
            .skipDefaultCallbacks(true)
            .initSql(setRole(ownerRole))
            // 기본 *:future 무시를 끈다: DB 이력에 이 이미지가 모르는 버전이 있으면 검증 실패
            .ignoreMigrationPatterns(new String[0])
            .validateOnMigrate(true)
            .outOfOrder(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true);
    verifyFlyway(configuration, dataSource, ownerRole, location);
    return configuration;
  }

  static String setRole(String ownerRole) {
    return "SET ROLE \"" + ownerRole + "\"";
  }

  private static Operation rejectArguments(String[] args) {
    Operation operation;
    if (Arrays.equals(args, new String[] {MIGRATE_ARGUMENT})) {
      operation = Operation.MIGRATE;
    } else if (Arrays.equals(args, new String[] {APPLY_GRANTS_ARGUMENT})) {
      operation = Operation.APPLY_GRANTS;
    } else if (Arrays.asList(args).contains(APPLY_GRANTS_ARGUMENT)) {
      throw new IllegalStateException("GRANT 실행 인자는 --apply-grants 하나만 받는다");
    } else {
      throw new IllegalStateException("Migration 실행 인자는 --migrate 하나만 받는다");
    }
    if (requestedProfileNames(args).stream().anyMatch(profile -> !isMigrationProfile(profile))) {
      throw new IllegalStateException("Migration 모드에는 앱 프로필을 함께 쓰지 않는다");
    }
    return operation;
  }

  /** HikariConfig 생성자가 이 시스템 속성의 파일을 읽어 설정을 채우므로 있으면 거절한다. */
  private static void rejectHikariConfigurationFile(Properties systemProperties) {
    if (systemProperties.getProperty("hikaricp.configurationFile") != null) {
      throw new IllegalStateException("Migration 모드에서는 Hikari 설정 파일을 쓰지 않는다");
    }
  }

  private static void verifyHikari(HikariConfig config, MigrationSettings.Values values) {
    boolean expected =
        values.url().equals(config.getJdbcUrl())
            && values.username().equals(config.getUsername())
            && values.password().equals(config.getPassword())
            && DRIVER.equals(config.getDriverClassName())
            && config.getDataSource() == null
            && config.getDataSourceClassName() == null
            && config.getDataSourceJNDI() == null
            && config.getDataSourceProperties().isEmpty()
            && config.getConnectionInitSql() == null
            && config.getCatalog() == null
            && config.getSchema() == null
            && config.getExceptionOverrideClassName() == null
            && config.getExceptionOverride() == null;
    if (!expected) {
      throw new IllegalStateException("Migration DB 연결 설정이 검증된 파일 값과 다르다");
    }
  }

  private static void verifyFlyway(
      FluentConfiguration configuration, DataSource dataSource, String ownerRole, String location) {
    boolean expected =
        configuration.getDataSource() == dataSource
            && configuration.getUrl() == null
            && configuration.getUser() == null
            && configuration.getPassword() == null
            && Arrays.equals(configuration.getLocations(), new Location[] {new Location(location)})
            && setRole(ownerRole).equals(configuration.getInitSql())
            && configuration.getPlaceholders().isEmpty()
            && configuration.getCallbacks().length == 0
            && configuration.getResolvers().length == 0
            && configuration.getSchemas().length == 0
            && configuration.getIgnoreMigrationPatterns().length == 0
            && configuration.isValidateOnMigrate()
            && !configuration.isOutOfOrder()
            && !configuration.isBaselineOnMigrate()
            && configuration.isFailOnMissingLocations()
            && configuration.isValidateMigrationNaming()
            && configuration.isSkipDefaultCallbacks()
            && configuration.isCleanDisabled();
    if (!expected) {
      throw new IllegalStateException("Migration Flyway 설정이 고정 계약과 다르다");
    }
  }

  /** 우리 코드의 메시지는 그대로, Flyway 검증 실패는 버전 차이만, 그 밖의 라이브러리 예외는 종류만 남긴다(값이 섞일 수 있으므로). */
  static String describe(RuntimeException exception) {
    if (exception instanceof GrantFailure grant) {
      return "GRANT SQL 실패(전체 ROLLBACK) sqlstate="
          + grant.sqlState
          + (grant.safeMessage == null ? "" : " message=" + grant.safeMessage);
    }
    if (exception instanceof FlywayValidateException validate) {
      return describeValidation(validate);
    }
    if (exception instanceof FlywayException flyway
        && !(flyway.getCause() instanceof SQLException)) {
      // 위치 없음·이름 오류 같은 구성 실패: 위치·파일 이름만 남는 고정 형태의 줄만 고른다
      String safe = safeFlywayLines(flyway);
      if (!safe.isEmpty()) {
        return "Flyway 구성 실패: " + safe;
      }
    }
    if (exception instanceof IllegalStateException
        && exception.getStackTrace().length > 0
        && exception.getStackTrace()[0].getClassName().startsWith("com.chilsuitjo.back")) {
      return exception.getMessage();
    }
    Throwable root = exception;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return exception.getClass().getSimpleName() + " (원인: " + root.getClass().getSimpleName() + ")";
  }

  private static final Pattern VALIDATION_LINE =
      Pattern.compile(
          "(Detected [A-Za-z ]+ migration[A-Za-z ]*: [0-9A-Za-z._-]{1,64}"
              + "|Migration checksum mismatch for migration version [0-9A-Za-z._-]{1,64})");

  /** Flyway 검증 실패: 버전 차이만 골라 남긴다(DB 주소·계정·SQL 본문은 남기지 않음). 복구는 사람이 한다. */
  static String describeValidation(FlywayValidateException exception) {
    List<String> details = new ArrayList<>();
    Matcher matcher = VALIDATION_LINE.matcher(String.valueOf(exception.getMessage()));
    while (matcher.find() && details.size() < 20) {
      details.add(matcher.group(1));
    }
    return "Flyway 검증 실패(DB 이력이 이 이미지의 Migration과 다르다. DB가 이미지보다 앞서 있을 수 있다."
        + " clean·repair·이력 수정은 자동으로 하지 않는다) code="
        + exception.getErrorCode()
        + " details="
        + details;
  }

  private static final Pattern SAFE_FLYWAY_LINE =
      Pattern.compile(
          "(Unable to resolve location [A-Za-z0-9:/._-]{1,200}"
              + "|Invalid [A-Za-z ]{1,40}: [A-Za-z0-9 ._,:/()-]{1,300}"
              + "|[A-Za-z ]{0,40}naming[A-Za-z ]{0,40}: [A-Za-z0-9 ._,:/()-]{1,300})");

  private static String safeFlywayLines(FlywayException exception) {
    List<String> lines = new ArrayList<>();
    Matcher matcher = SAFE_FLYWAY_LINE.matcher(String.valueOf(exception.getMessage()));
    while (matcher.find() && lines.size() < 10) {
      lines.add(matcher.group(1).trim());
    }
    return String.join(" / ", lines);
  }

  private static List<String> requestedProfileNames(String[] args) {
    List<String> sources = new ArrayList<>();
    sources.add(System.getenv("SPRING_PROFILES_ACTIVE"));
    sources.add(System.getProperty("spring.profiles.active"));
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument.startsWith("--spring.profiles.active=")) {
        sources.add(argument.substring("--spring.profiles.active=".length()));
      } else if (argument.equals("--spring.profiles.active") && index + 1 < args.length) {
        sources.add(args[index + 1]);
      }
    }
    return sources.stream()
        .filter(value -> value != null && !value.isBlank())
        .flatMap(value -> Arrays.stream(value.split(",")))
        .map(String::trim)
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .toList();
  }

  private static boolean isMigrationProfile(String profile) {
    return profile.equals(MIGRATE_PROFILE) || profile.equals(LEGACY_MIGRATION_PROFILE);
  }
}
