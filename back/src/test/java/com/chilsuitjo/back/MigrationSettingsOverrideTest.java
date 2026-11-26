package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.config.migration.MigrationSettings;
import com.zaxxer.hikari.HikariConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 외부 출처(인자·시스템 속성·환경변수·JSON)가 최종 Hikari·Flyway 설정 객체에 닿지 않는지 본다.
 *
 * <p>통과 조건은 (1) 입력 자체를 거절하거나 (2) 입력이 있어도 최종 설정 객체가 파일 값·고정 계약과 같은 것. DB·AWS에 연결하지 않는다.
 */
// 운영 Migration은 Linux 컨테이너에서만 돈다. 설정 계약(sslrootcert는 POSIX 절대 경로, 파일 권한·심볼릭 링크·FIFO 거절)을
// 그대로 시험하므로 Windows에서는 건너뛴다(CI는 Linux에서 모두 돈다, #173 리뷰)
@EnabledOnOs(
    value = {OS.LINUX, OS.MAC},
    disabledReason = "운영 Migration 설정은 POSIX 경로·권한 계약이다(Linux 컨테이너 전용)")
class MigrationSettingsOverrideTest {

  /** 내용 검사는 드라이버 몫이라 여기서는 읽을 수 있는 일반 파일이면 된다(값 검증 단위 테스트용). */
  static final Path ROOT_CERT = placeholderCertificate();

  static final String URL =
      "jdbc:postgresql://127.0.0.1:1/homestamp?sslmode=verify-full&sslrootcert=" + ROOT_CERT;
  static final String USER = "homestamp_migration";
  static final String PASSWORD = "MIGRATION-TEST-FAKE-PASSWORD";
  static final String OWNER = "homestamp_owner";
  static final String ATTACK_URL = "jdbc:postgresql://attacker:5432/x";

  /** 연결하지 않는 DataSource(Flyway 설정 객체 검사용). */
  static final DataSource NO_CONNECT =
      (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> {
                throw new AssertionError("설정 검사 중 연결을 시도했다");
              });

  @TempDir Path root;
  Path dir;

  @BeforeEach
  void files() throws Exception {
    dir = Files.createDirectory(root.resolve("homestamp-migration"));
    Files.writeString(dir.resolve("spring.datasource.url"), URL);
    Files.writeString(dir.resolve("spring.datasource.username"), USER);
    Files.writeString(dir.resolve("spring.datasource.password"), PASSWORD);
    Files.writeString(dir.resolve("app.migration.owner-role"), OWNER);
  }

  MigrationMode.Prepared prepare(String[] args, Properties systemProperties) {
    return MigrationMode.prepare(args, systemProperties, new MigrationSettings(dir));
  }

  /** 최종 객체가 파일 값·고정 계약과 같은지(값은 출력하지 않고 비교만). */
  static Path placeholderCertificate() {
    try {
      Path certificate = Files.createTempFile("migration-test-root", ".crt");
      certificate.toFile().deleteOnExit();
      return certificate.toAbsolutePath();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static void assertTrusted(MigrationMode.Prepared prepared) {
    assertTrusted(prepared, URL);
  }

  static void assertTrusted(MigrationMode.Prepared prepared, String expectedUrl) {
    HikariConfig hikari = prepared.hikari();
    assertThat(hikari.getJdbcUrl()).isEqualTo(expectedUrl);
    assertThat(hikari.getUsername()).isEqualTo(USER);
    assertThat(hikari.getPassword().equals(PASSWORD)).isTrue();
    assertThat(hikari.getConnectionInitSql()).isNull();
    assertThat(hikari.getDataSourceProperties()).isEmpty();
    assertThat(hikari.getDataSourceJNDI()).isNull();
    assertThat(hikari.getDataSourceClassName()).isNull();
    assertThat(prepared.ownerRole()).isEqualTo(OWNER);
    FluentConfiguration flyway =
        MigrationMode.flywayConfiguration(NO_CONNECT, prepared.ownerRole());
    assertThat(flyway.getUrl()).isNull();
    assertThat(flyway.getUser()).isNull();
    assertThat(flyway.getPassword()).isNull();
    assertThat(flyway.getLocations()).containsExactly(new Location("classpath:db/migration"));
    assertThat(flyway.getInitSql()).isEqualTo("SET ROLE \"" + OWNER + "\"");
    assertThat(flyway.getPlaceholders()).isEmpty();
    assertThat(flyway.getCallbacks()).isEmpty();
    assertThat(flyway.getIgnoreMigrationPatterns()).isEmpty();
    assertThat(flyway.isValidateOnMigrate()).isTrue();
    assertThat(flyway.isOutOfOrder()).isFalse();
    assertThat(flyway.isBaselineOnMigrate()).isFalse();
    assertThat(flyway.isValidateMigrationNaming()).isTrue();
    assertThat(flyway.isFailOnMissingLocations()).isTrue();
    assertThat(flyway.isSkipDefaultCallbacks()).isTrue();
  }

  @Test
  void 정상_인자_하나면_파일_값으로만_구성한다() {
    assertTrusted(prepare(new String[] {"--migrate"}, new Properties()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--spring.datasource.url=" + ATTACK_URL,
        "--spring.datasource.hikari.jdbc-url=" + ATTACK_URL,
        "--spring.datasource.hikari.username=attacker",
        "--spring.application.json={\"spring.datasource.username\":\"attacker\"}",
        "--spring.config.additional-location=file:/tmp/attack/",
        "--spring.flyway.locations=filesystem:/tmp/attack",
        "--spring.profiles.active=prod",
      })
  void 명령_인자는_migrate_말고_모두_거절한다(String attack) {
    assertThatThrownBy(() -> prepare(new String[] {"--migrate", attack}, new Properties()))
        .hasMessage("Migration 실행 인자는 --migrate 하나만 받는다");
  }

  @Test
  void 시스템_속성은_최종_설정에_닿지_않는다() {
    Map<String, String> attacks = new LinkedHashMap<>();
    attacks.put("spring.datasource.url", ATTACK_URL);
    attacks.put("spring.datasource.password", "attacker");
    attacks.put("spring.datasource.hikari.jdbc-url", ATTACK_URL);
    attacks.put("spring.datasource.hikari.connection-init-sql", "RESET ROLE");
    attacks.put("spring.datasource.hikari.data-source-properties.options", "-c role=postgres");
    attacks.put("spring.datasource.jndi-name", "java:comp/env/jdbc/attack");
    attacks.put(
        "spring.datasource.type", "org.springframework.jdbc.datasource.SimpleDriverDataSource");
    attacks.put("spring.application.json", "{\"spring.datasource.username\":\"attacker\"}");
    attacks.put("spring.flyway.url", ATTACK_URL);
    attacks.put("spring.flyway.user", "attacker");
    attacks.put("spring.flyway.password", "attacker");
    attacks.put("spring.flyway.locations", "filesystem:/tmp/attack");
    attacks.put("spring.flyway.init-sqls", "RESET ROLE");
    attacks.put("spring.flyway.placeholders.x", "attack");
    attacks.put("flyway.url", ATTACK_URL);
    attacks.put("flyway.locations", "filesystem:/tmp/attack");
    attacks.put("flyway.initSql", "RESET ROLE");
    attacks.put("flyway.placeholders.x", "attack");
    attacks.put("flyway.ignoreMigrationPatterns", "*:future");
    attacks.put("spring.flyway.ignore-migration-patterns", "*:future");
    attacks.put("flyway.outOfOrder", "true");
    attacks.put("flyway.baselineOnMigrate", "true");
    attacks.put("flyway.validateMigrationNaming", "false");
    attacks.put("flyway.failOnMissingLocations", "false");
    attacks.put("flyway.skipDefaultCallbacks", "false");
    // pgjdbc 기본값 경로(드라이버가 쓰는 시스템 속성 이름)도 최종 URL을 바꾸지 못한다
    attacks.put("sslmode", "disable");
    Properties saved = (Properties) System.getProperties().clone();
    try {
      attacks.forEach(System::setProperty);
      assertTrusted(prepare(new String[] {"--migrate"}, System.getProperties()));
    } finally {
      System.setProperties(saved);
    }
  }

  @Test
  void Hikari_설정_파일_시스템_속성은_거절한다() {
    Properties properties = new Properties();
    properties.setProperty("hikaricp.configurationFile", "/tmp/attack.properties");
    assertThatThrownBy(() -> prepare(new String[] {"--migrate"}, properties))
        .hasMessage("Migration 모드에서는 Hikari 설정 파일을 쓰지 않는다");
  }

  /** 환경변수는 JVM 안에서 바꿀 수 없으므로 별도 JVM에서 같은 코드(prepare·flywayConfiguration)를 돌린다. */
  @Test
  void 환경변수는_별도_JVM에서도_최종_설정에_닿지_않는다() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            MigrationSettingsOverrideProbe.class.getName(),
            dir.toString());
    Map<String, String> environment = builder.environment();
    List.of(
            "SPRING_DATASOURCE_URL",
            "spring_datasource_url",
            "SPRING_DATASOURCE_HIKARI_JDBC_URL",
            "SPRING_FLYWAY_URL",
            "FLYWAY_URL",
            "PGHOST")
        .forEach(name -> environment.put(name, name.equals("PGHOST") ? "attacker" : ATTACK_URL));
    environment.put("spring.datasource.password", "attacker");
    environment.put("SPRING_DATASOURCE_HIKARI_USERNAME", "attacker");
    environment.put("SPRING_DATASOURCE_HIKARI_PASSWORD", "attacker");
    environment.put("SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL", "RESET ROLE");
    environment.put("SPRING_APPLICATION_JSON", "{\"spring.datasource.username\":\"attacker\"}");
    environment.put("FLYWAY_LOCATIONS", "filesystem:/tmp/attack");
    environment.put("FLYWAY_INIT_SQL", "RESET ROLE");
    environment.put("FLYWAY_PLACEHOLDERS_X", "attack");
    environment.put("FLYWAY_USER", "attacker");
    environment.put("FLYWAY_IGNORE_MIGRATION_PATTERNS", "*:future");
    environment.put("SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS", "*:future");
    environment.put("FLYWAY_VALIDATE_ON_MIGRATE", "false");
    environment.put("FLYWAY_VALIDATE_MIGRATION_NAMING", "false");
    environment.put("FLYWAY_SKIP_DEFAULT_CALLBACKS", "false");
    environment.put("PGSSLMODE", "disable");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(output).doesNotContain(PASSWORD);
    assertThat(output).contains("env_seen=true", "trusted=true");
    assertThat(process.exitValue()).isZero();
  }
}
