package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.chilsuitjo.back.global.config.migration.MigrationSettings;
import com.chilsuitjo.back.global.config.ssm.SsmConfigParameter;
import com.chilsuitjo.back.global.config.ssm.SsmTestParameterSource;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 실제 PostgreSQL 18에서 Migration 로그인 → SET ROLE owner → Flyway V1~V8을 돌리고 실행 주체·객체 소유권·이력·재실행·실패를 본다.
 *
 * <p>역할은 #185 {@code bootstrap_roles.sql}(7fc6d1b)과 같은 계약으로 만든다. 실행 주체를 보려고 테스트 DB에만 관리자 소유 event
 * trigger(DDL마다 session_user·current_user를 서버 로그에 남김)를 붙인다. 운영 Migration 파일·grants.sql은 쓰지 않는다. 값은
 * 출력하지 않는다.
 */
class MigrationRunnerIntegrationTest {

  static final String OWNER = "hs_owner";
  static final String MIGRATION = "hs_migration";
  static final String APP = "hs_app";
  static final String PURGE = "hs_purge";
  static final String HUMAN = "hs_human";
  static final String PASSWORD = "MIGRATION-RUNNER-FAKE-PASSWORD";
  static final String SCHEMAS = "('public','record','state','log')";
  static final Path JAR =
      Path.of(
          System.getProperty(
              "migration.test.production-boot-jar", "build/libs/back-0.0.1-SNAPSHOT.jar"));
  // 운영 Dockerfile 런타임과 같은 base 이미지(UID 10002로 실행)
  static final String RUNTIME_IMAGE = "eclipse-temurin:25-jre-noble";
  static final String SECRET_DIR = "/run/secrets/homestamp-migration/";

  static final Network INTERNAL =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withInternal(true)).build();
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  static {
    POSTGRES.withLabel("hs-migration-test", "1");
  }

  /** 운영과 같은 verify-full 접속을 위해 테스트마다 새로 만드는 자체 서명 인증서(개인 키는 저장소에 두지 않는다). */
  static TestTls tls;

  /** 다른 신뢰 루트: 이 인증서로는 서버 인증서를 검증하지 못해야 한다. */
  static TestTls otherTls;

  @TempDir static Path tlsDir;

  static boolean rolesCreated;

  @TempDir Path temp;

  @BeforeAll
  static void start() throws Exception {
    String host = POSTGRES.getHost();
    tls = TestTls.generate(tlsDir.resolve("server"), List.of("localhost", "hs-pg", host));
    otherTls = TestTls.generate(tlsDir.resolve("other"), List.of("localhost", "hs-pg", host));
    POSTGRES.withCopyToContainer(Transferable.of(tls.certificatePem(), 0644), "/tls/server.crt");
    POSTGRES.withCopyToContainer(Transferable.of(tls.privateKeyPem(), 0600), "/tls/server.key");
    // 이미지 entrypoint는 root로 시작한다. 키를 postgres 소유 0600으로 옮긴 뒤 원래 entrypoint로 넘긴다
    POSTGRES.withCreateContainerCmdModifier(cmd ->
        cmd.withEntrypoint("sh", "-c")
            .withCmd(
                "mkdir -p /tmp/pgtls && cp /tls/server.crt /tls/server.key /tmp/pgtls/"
                    + " && chown -R postgres:postgres /tmp/pgtls && chmod 600 /tmp/pgtls/server.key"
                    + " && exec docker-entrypoint.sh postgres -c fsync=off -c log_statement=all"
                    + " -c 'log_line_prefix=%u|%d|%h|' -c ssl=on"
                    + " -c ssl_cert_file=/tmp/pgtls/server.crt -c ssl_key_file=/tmp/pgtls/server.key"));
    POSTGRES.start();
    DockerClient docker = DockerClientFactory.instance().client();
    docker
        .connectToNetworkCmd()
        .withContainerId(POSTGRES.getContainerId())
        .withNetworkId(INTERNAL.getId())
        .withContainerNetwork(new ContainerNetwork().withAliases("hs-pg"))
        .exec();
  }

  @AfterAll
  static void stop() {
    assertThat(POSTGRES.getLogs()).as("PG 서버 로그에 가짜 비밀번호가 없다").doesNotContain(PASSWORD);
    POSTGRES.stop();
    INTERNAL.close();
  }

  // ---------------------------------------------------------------- 정상·소유권·이력·재실행

  @Test
  void 정상_실행은_owner로_전환해_V1부터_V8까지_만들고_재실행은_아무것도_바꾸지_않는다() throws Exception {
    String db = "hs_main";
    prepareDatabase(db);
    roleContract(db);
    int logStart = POSTGRES.getLogs().length();

    migrate(db, MIGRATION, OWNER);

    String log = POSTGRES.getLogs().substring(logStart);
    // D. 실제 DDL 실행 주체: 모든 DDL이 session=migration, current=owner
    List<String[]> ddl = ddlAudit(log, db);
    assertThat(ddl).isNotEmpty();
    assertThat(ddl)
        .allSatisfy(row -> assertThat(row[0] + "/" + row[1]).isEqualTo(MIGRATION + "/" + OWNER));
    report("ddl_audit count=" + ddl.size() + " pairs=" + pairs(ddl) + " tags=" + tags(ddl));
    // 이 DB에 접속한 사용자와 실행한 문장 종류
    Map<String, Integer> verbs = statementVerbs(log, db, MIGRATION);
    report("db_users=" + users(log, db) + " migration_verbs=" + verbs);
    assertThat(users(log, db)).containsOnly(MIGRATION);
    // 권한·역할을 바꾸는 문장은 V1의 함수 REVOKE 2건(PUBLIC 실행 권한 회수)만 있어야 한다
    List<String> executed = statements(log, db, MIGRATION);
    assertThat(executed)
        .filteredOn(s ->
            s.matches(
                "(?is)\\s*(GRANT|REVOKE|RESET\\s+ROLE|SET\\s+SESSION|ALTER\\s+ROLE|CREATE\\s+ROLE|ALTER\\s+DEFAULT\\s+PRIVILEGES|DROP\\s+OWNED|REASSIGN|SET\\s+ROLE).*"))
        .allSatisfy(s ->
            assertThat(s)
                .matches(
                    "(?is)\\s*(SET ROLE \"?'?hs_owner'?\"?|REVOKE ALL ON FUNCTION (state\\.purge_room_record|record\\.purge_member_acknowledgements)\\(.*\\) FROM PUBLIC)\\s*"));
    int firstSetRole = indexOf(executed, "(?is)\\s*SET ROLE.*");
    int firstChange = indexOf(executed, "(?is)\\s*(CREATE|ALTER|INSERT|UPDATE|DELETE|REVOKE).*");
    report(
        "first_set_role_index="
            + firstSetRole
            + " first_change_index="
            + firstChange
            + " set_role_forms="
            + new TreeSet<>(
                executed.stream().filter(s -> s.matches("(?is)\\s*SET ROLE.*")).toList()));
    assertThat(firstSetRole).isNotNegative().isLessThan(firstChange);

    // E. 객체 소유권: 4개 스키마 안 모든 relation·함수·타입이 owner, record·state·log 스키마도 owner
    List<String> snapshot = snapshot(db);
    Map<String, Integer> byKindOwner = new TreeMap<>();
    for (String row : snapshot) {
      String[] p = row.split("\\|");
      byKindOwner.merge(p[0] + "@" + p[2], 1, Integer::sum);
    }
    report("owners " + byKindOwner);
    assertThat(snapshot)
        .filteredOn(row -> !row.startsWith("schema|public|"))
        .allSatisfy(row -> assertThat(row).endsWith("|" + OWNER));
    assertThat(snapshot)
        .contains("schema|record|" + OWNER, "schema|state|" + OWNER, "schema|log|" + OWNER);
    report(
        "public_schema_owner="
            + one(db, "SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname='public'"));
    report(
        "extensions="
            + list(db, "SELECT extname||'@'||extowner::regrole FROM pg_extension ORDER BY 1"));
    report(
        "default_acl="
            + list(db, "SELECT defaclrole::regrole||':'||defaclobjtype::text FROM pg_default_acl"));
    report(
        "owned_by_others="
            + list(
                db,
                """
                SELECT kind||':'||ident||'@'||owner FROM (
                  SELECT 'rel' kind, n.nspname||'.'||c.relname ident, c.relowner::regrole::text owner
                  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname NOT IN ('pg_catalog','information_schema','pg_toast','hsaudit')
                  UNION ALL SELECT 'proc', p.oid::regprocedure::text, p.proowner::regrole::text
                  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                  WHERE n.nspname NOT IN ('pg_catalog','information_schema','hsaudit')) s
                WHERE owner <> 'hs_owner' ORDER BY 1\
                """));

    // F. 이력 위치·소유자·행
    assertThat(
            list(
                db,
                "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname='flyway_schema_history'"))
        .containsExactly("public");
    assertThat(
            one(
                db,
                "SELECT relowner::regrole::text FROM pg_class WHERE oid='public.flyway_schema_history'::regclass"))
        .isEqualTo(OWNER);
    List<String> history = history(db);
    history.forEach(row -> report("history " + row));
    assertThat(history).hasSize(8);
    assertThat(history).allSatisfy(row -> assertThat(row).contains("|true|"));
    report(
        "history_acl="
            + one(
                db,
                "SELECT coalesce(relacl::text,'(default)') FROM pg_class WHERE oid='public.flyway_schema_history'::regclass"));

    // 실행 뒤에도 Migration 로그인 자체는 owner 권한을 상속하지 않는다
    loginWithoutSetRole(db);
    roleContract(db);

    // G. 같은 DB 재실행: 실행 0건, 객체·소유자·이력 그대로, DDL 0건
    List<String> fullHistory =
        list(
            db,
            "SELECT installed_rank||'|'||version||'|'||checksum||'|'||installed_on FROM public.flyway_schema_history ORDER BY 1");
    int rerunStart = POSTGRES.getLogs().length();
    migrate(db, MIGRATION, OWNER);
    String rerunLog = POSTGRES.getLogs().substring(rerunStart);
    assertThat(snapshot(db)).isEqualTo(snapshot);
    assertThat(
            list(
                db,
                "SELECT installed_rank||'|'||version||'|'||checksum||'|'||installed_on FROM public.flyway_schema_history ORDER BY 1"))
        .isEqualTo(fullHistory);
    assertThat(ddlAudit(rerunLog, db)).isEmpty();
    report(
        "rerun verbs="
            + statementVerbs(rerunLog, db, MIGRATION)
            + " ddl=0 snapshot_equal=true history_equal=true");
  }

  // ---------------------------------------------------------------- 실패

  @Test
  void owner로_전환할_권한이_없는_로그인은_아무것도_만들지_못한다() throws Exception {
    assertFailsWithoutChanges("hs_f_noset", APP, OWNER);
  }

  @Test
  void 없는_owner_이름이면_아무것도_만들지_못한다() throws Exception {
    assertFailsWithoutChanges("hs_f_owner", MIGRATION, "hs_nobody");
  }

  @Test
  void 틀린_비밀번호면_아무것도_만들지_못한다() throws Exception {
    String db = "hs_f_pw";
    prepareDatabase(db);
    Path files = files(migrationUrl(db), MIGRATION, "wrong-" + PASSWORD.length(), OWNER);
    Throwable failure = catchRun(files);
    report(db + " failure=" + chain(failure));
    assertThat(chainMessages(failure)).doesNotContain(PASSWORD);
    assertNothingCreated(db);
  }

  @Test
  void 체크섬_불일치_누락_미래_버전은_모두_실패하고_DB를_바꾸지_않는다() throws Exception {
    String db = "hs_f_drift";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    List<String> before = snapshot(db);

    assertDriftRejected(
        db,
        "checksum_mismatch",
        "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '3'",
        "Migration checksum mismatch for migration version 3",
        before);
    admin(
        db, "UPDATE public.flyway_schema_history SET checksum = checksum - 1 WHERE version = '3'");

    assertDriftRejected(
        db, "missing_applied_4.1", historyRow("4.1", "test missing locally"), ": 4.1", before);
    admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '4.1'");

    for (String future : List.of("9", "99")) {
      assertDriftRejected(
          db, "future_V" + future, historyRow(future, "test future"), ": " + future, before);
      admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '" + future + "'");
    }
    // 이력을 되돌리면 다시 정상(실패가 DB를 망가뜨리지 않았다)
    migrate(db, MIGRATION, OWNER);
    assertThat(snapshot(db)).isEqualTo(before);
  }

  void assertDriftRejected(
      String db, String name, String drift, String expectedDetail, List<String> before)
      throws Exception {
    admin(db, drift);
    String historyQuery =
        "SELECT installed_rank||'|'||coalesce(version,'-')||'|'||coalesce(checksum::text,'-')||'|'||installed_on||'|'||success FROM public.flyway_schema_history ORDER BY 1";
    List<String> historyBefore = list(db, historyQuery);
    int logStart = POSTGRES.getLogs().length();
    Throwable failure = catchRun(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER));
    String log = POSTGRES.getLogs().substring(logStart);
    assertThat(failure).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
    String described =
        MigrationMode.describeValidation(
            (org.flywaydb.core.api.exception.FlywayValidateException) failure);
    report(
        name
            + " failure="
            + chain(failure)
            + " described="
            + described
            + " ddl="
            + ddlAudit(log, db).size()
            + " verbs="
            + statementVerbs(log, db, MIGRATION));
    assertThat(described)
        .contains("Flyway 검증 실패", expectedDetail)
        .doesNotContain(PASSWORD, "jdbc:", MIGRATION);
    assertThat(ddlAudit(log, db)).isEmpty();
    assertThat(statementVerbs(log, db, MIGRATION).keySet())
        .noneMatch(v -> v.matches("CREATE.*|ALTER.*|INSERT|UPDATE|DELETE|DROP.*|GRANT|REVOKE"));
    assertThat(snapshot(db)).isEqualTo(before);
    assertThat(list(db, historyQuery)).isEqualTo(historyBefore);
  }

  static String historyRow(String version, String description) {
    return "INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
        + "SELECT max(installed_rank)+1,'"
        + version
        + "','"
        + description
        + "','SQL','V"
        + version
        + "__test.sql',1,'hs_owner',0,true FROM public.flyway_schema_history";
  }

  @Test
  void 중간_SQL이_실패하면_V1은_되돌아가고_이력_테이블만_남는다() throws Exception {
    String db = "hs_f_mid";
    prepareDatabase(db);
    // bootstrap 뒤 드리프트: 관리자가 state 스키마를 먼저 만든다(V1은 IF NOT EXISTS로 건너뛰고 그 안에 함수를 만들다 실패)
    admin(db, "CREATE SCHEMA state");
    Throwable failure = catchRun(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER));
    report(db + " failure=" + chain(failure));
    report(
        db
            + " left schemas="
            + list(
                db,
                "SELECT nspname||'@'||nspowner::regrole FROM pg_namespace WHERE nspname IN ('record','state','log') ORDER BY 1")
            + " history_rows="
            + list(
                db,
                "SELECT coalesce(version,'-')||'|'||success FROM public.flyway_schema_history ORDER BY installed_rank")
            + " relations="
            + list(
                db,
                "SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN "
                    + SCHEMAS
                    + " ORDER BY 1"));
    assertThat(list(db, "SELECT nspname FROM pg_namespace WHERE nspname IN ('record','log')"))
        .isEmpty();
    assertThat(list(db, "SELECT version FROM public.flyway_schema_history WHERE success"))
        .isEmpty();
  }

  // ---------------------------------------------------------------- TLS(verify-full)

  @Test
  void 서버_인증서를_신뢰_루트로_검증하지_못하면_연결하지_않고_실패한다() throws Exception {
    String db = "hs_f_tls";
    prepareDatabase(db);
    String untrusted = url(db) + "?sslmode=verify-full&sslrootcert=" + otherTls.rootCertificate();
    Throwable failure = catchRun(files(untrusted, MIGRATION, PASSWORD, OWNER));
    report(
        db
            + " failure="
            + chain(failure)
            + " described="
            + MigrationMode.describe((RuntimeException) failure));
    assertThat(chainMessages(failure)).doesNotContain(PASSWORD);
    assertThat(MigrationMode.describe((RuntimeException) failure))
        .doesNotContain("jdbc:", MIGRATION);
    assertNothingCreated(db);
  }

  @Test
  void Migration_연결은_실제로_TLS를_쓴다() throws Exception {
    String db = "hs_tls_on";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    // 테스트 PG는 ssl=on이고 hostssl 강제는 아니다. 그래서 운영 계약(verify-full)이 실제 TLS 세션을 만드는지 서버 쪽에서 본다
    try (Connection c = DriverManager.getConnection(migrationUrl(db), MIGRATION, PASSWORD);
        Statement s = c.createStatement()) {
      assertThat(query(s, "SELECT ssl||'|'||version FROM pg_stat_ssl WHERE pid = pg_backend_pid()"))
          .startsWith("true|TLS");
    }
  }

  // ---------------------------------------------------------------- Flyway 위치·이름·콜백

  @Test
  void Migration_위치가_없으면_실패하고_DB를_바꾸지_않는다() throws Exception {
    String db = "hs_f_noloc";
    prepareDatabase(db);
    Throwable failure =
        catchRunAt(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER),
            "classpath:migration-test/missing");
    report(
        db
            + " failure="
            + chain(failure)
            + " described="
            + MigrationMode.describe((RuntimeException) failure));
    assertThat(MigrationMode.describe((RuntimeException) failure))
        .contains("migration-test/missing");
    assertNothingCreated(db);
  }

  @Test
  void 위치는_있지만_SQL_Migration이_없으면_실패하고_DB를_바꾸지_않는다() throws Exception {
    String db = "hs_f_emptyloc";
    prepareDatabase(db);
    Throwable failure =
        catchRunAt(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER), "classpath:migration-test/empty");
    report(
        db
            + " failure="
            + chain(failure)
            + " described="
            + MigrationMode.describe((RuntimeException) failure));
    assertThat(failure).hasMessage("적용하거나 검증할 Migration이 하나도 없다");
    assertNothingCreated(db);
  }

  @Test
  void failOnMissingLocations만으로는_빈_위치가_성공하므로_별도_검사가_필요하다() throws Exception {
    // Flyway 12.4.0 실제 동작 기록: 위치가 존재하면 SQL이 0개여도 migrate가 성공(0건)한다. 동작이 바뀌면 이 테스트가 알려 준다
    String db = "hs_flyway_emptyloc";
    prepareDatabase(db);
    MigrationSettings.Values values =
        new MigrationSettings(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER)).load();
    try (com.zaxxer.hikari.HikariDataSource dataSource = new com.zaxxer.hikari.HikariDataSource()) {
      dataSource.setJdbcUrl(values.url());
      dataSource.setUsername(values.username());
      dataSource.setPassword(values.password());
      Flyway flyway =
          MigrationMode.flywayConfiguration(dataSource, OWNER, "classpath:migration-test/empty")
              .load();
      assertThat(flyway.getConfiguration().isFailOnMissingLocations()).isTrue();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
  }

  @Test
  void 이름이_규칙에_맞지_않는_SQL_파일이_있으면_아무것도_적용하지_않고_실패한다() throws Exception {
    String db = "hs_f_badname";
    prepareDatabase(db);
    int logStart = POSTGRES.getLogs().length();
    Throwable failure =
        catchRunAt(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER),
            "classpath:migration-test/badname");
    String log = POSTGRES.getLogs().substring(logStart);
    String described = MigrationMode.describe((RuntimeException) failure);
    report(
        db
            + " failure="
            + chain(failure)
            + " described="
            + described
            + " ddl="
            + ddlAudit(log, db).size());
    assertThat(described).contains("V2_bad_name.sql").doesNotContain(PASSWORD, "jdbc:");
    assertThat(ddlAudit(log, db)).isEmpty();
    assertNothingCreated(db);
  }

  @Test
  void 위치_안의_SQL_콜백은_실행하지_않는다() throws Exception {
    String db = "hs_callback";
    prepareDatabase(db);
    MigrationMode.run(
        new String[] {"--migrate"},
        new Properties(),
        new MigrationSettings(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER)),
        "classpath:migration-test/callback");
    assertThat(one(db, "SELECT coalesce(to_regclass('public.migration_probe')::text,'none')"))
        .isEqualTo("migration_probe");
    assertThat(
            one(db, "SELECT coalesce(to_regclass('public.migration_callback_ran')::text,'none')"))
        .isEqualTo("none");
  }

  // ---------------------------------------------------------------- 실제 --migrate CLI (UID 10002,
  // internal 네트워크)

  @Test
  void 실제_CLI_프로세스는_UID_10002로_성공_0_실패_1을_돌려주고_파일의_주소만_쓴다() throws Exception {
    assumeTrue(Files.isRegularFile(JAR), "bootJar 먼저 빌드");
    String db = "hs_cli";
    prepareDatabase(db);

    Cli first = cli(db, MIGRATION, PASSWORD, Map.of());
    report("cli normal exit=" + first.exit() + " " + first.summary());
    assertThat(first.exit()).isZero();
    assertThat(history(db)).hasSize(8);

    Map<String, String> attack = new LinkedHashMap<>();
    attack.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("SPRING_DATASOURCE_HIKARI_JDBC_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("SPRING_DATASOURCE_USERNAME", "hs_attacker");
    attack.put("SPRING_APPLICATION_JSON", "{\"spring.datasource.username\":\"hs_attacker\"}");
    attack.put("FLYWAY_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("FLYWAY_LOCATIONS", "filesystem:/tmp");
    attack.put("PGHOST", "hs-attacker");
    int logStart = POSTGRES.getLogs().length();
    Cli rerun = cli(db, MIGRATION, PASSWORD, attack);
    String log = POSTGRES.getLogs().substring(logStart);
    report(
        "cli rerun+attack_env exit="
            + rerun.exit()
            + " "
            + rerun.summary()
            + " db_users="
            + users(log, db)
            + " hosts="
            + hosts(log, db)
            + " other_db_lines="
            + otherDbUsers(log, db));
    assertThat(rerun.exit()).isZero();
    assertThat(users(log, db)).containsOnly(MIGRATION);
    assertThat(otherDbUsers(log, db)).doesNotContain("hs_attacker");

    Cli wrong = cli(db, MIGRATION, "wrong-" + PASSWORD.length(), Map.of());
    report("cli wrong_password exit=" + wrong.exit() + " " + wrong.summary());
    assertThat(wrong.exit()).isEqualTo(1);
    assertThat(history(db)).hasSize(8);

    // 미래 버전: DB 이력에 이 이미지가 모르는 V99 → 종료 1, 이력·객체 그대로
    admin(db, historyRow("99", "test future"));
    List<String> before = snapshot(db);
    List<String> historyBefore = history(db);
    Cli future = cli(db, MIGRATION, PASSWORD, Map.of());
    report("cli future_V99 exit=" + future.exit() + " " + future.summary());
    assertThat(future.exit()).isEqualTo(1);
    assertThat(future.logs()).contains("Flyway 검증 실패", ": 99").doesNotContain(PASSWORD);
    assertThat(snapshot(db)).isEqualTo(before);
    assertThat(history(db)).isEqualTo(historyBefore);
  }

  // ---------------------------------------------------------------- 앱 프로필의 Flyway(운영 앱은 Migration을
  // 하지 않는다)

  @Test
  void 운영_프로필의_앱은_Flyway를_끄고_DB를_바꾸지_않는다() throws Exception {
    for (String profiles : List.of("prod", "prod,test", "test,prod")) {
      String db = "hs_app_" + profiles.replace(",", "_");
      admin(POSTGRES.getDatabaseName(), "CREATE DATABASE " + db);
      assertFlywayProfile(db, profiles, false);
      assertThat(
              one(db, "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'none')"))
          .isEqualTo("none");
    }
  }

  @Test
  void local과_test_프로필은_Flyway를_켜고_V1부터_V8까지_적용한다() throws Exception {
    for (String profiles : List.of("local", "test")) {
      String db = "hs_app_" + profiles;
      admin(POSTGRES.getDatabaseName(), "CREATE DATABASE " + db);
      assertFlywayProfile(db, profiles, true);
      assertThat(
              list(
                  db,
                  "SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
          .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }
  }

  @Test
  void 운영_bootJar에는_테스트용_SSM_출처가_없다() throws Exception {
    assumeTrue(Files.isRegularFile(JAR), "bootJar 먼저 빌드");
    try (JarFile jar = new JarFile(JAR.toFile())) {
      assertThat(
              jar.getEntry(
                  "BOOT-INF/classes/com/chilsuitjo/back/global/config/ssm/SsmTestParameterSource.class"))
          .isNull();
      assertThat(
              jar.getEntry(
                  "BOOT-INF/classes/com/chilsuitjo/back/global/config/ssm/SsmTestBootstrapRegistryInitializer.class"))
          .isNull();
      assertThat(jar.getEntry("BOOT-INF/classes/com/chilsuitjo/back/MigrationMode.class"))
          .isNotNull();
      assertThat(
              jar.getEntry(
                  "BOOT-INF/classes/com/chilsuitjo/back/global/config/migration/MigrationSettings.class"))
          .isNotNull();
    }
  }

  static void assertFlywayProfile(String db, String profiles, boolean enabled) {
    SsmTestParameterSource.setParameters(
        appParameters(url(db), POSTGRES.getUsername(), POSTGRES.getPassword()));
    try {
      SpringApplication application = new SpringApplication(FlywayOnlyConfiguration.class);
      application.setWebApplicationType(WebApplicationType.NONE);
      application.setAdditionalProfiles(profiles.split(","));
      application.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
      String[] arguments =
          profiles.contains("prod")
              ? new String[0]
              : new String[] {
                "--spring.datasource.url=" + url(db),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()
              };
      try (ConfigurableApplicationContext context = application.run(arguments)) {
        assertThat(
                context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class, true))
            .isEqualTo(enabled);
        assertThat(context.getBeansOfType(Flyway.class)).hasSize(enabled ? 1 : 0);
      }
    } finally {
      SsmTestParameterSource.reset();
    }
  }

  static List<SsmConfigParameter> appParameters(String url, String username, String password) {
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

  // ---------------------------------------------------------------- helpers

  record Cli(long exit, String logs) {
    String summary() {
      Matcher m = Pattern.compile("Migration을 실행하지 못했다: [^\\n]*").matcher(logs);
      return "marker="
          + logs.contains(PASSWORD)
          + " debug="
          + logs.contains(" DEBUG ")
          + " reason=["
          + (m.find() ? m.group() : "-")
          + "] successLine="
          + logs.contains("Successfully applied")
          + "/"
          + logs.contains("is up to date");
    }
  }

  static Cli cli(String db, String user, String password, Map<String, String> env) {
    String url =
        "jdbc:postgresql://hs-pg:5432/"
            + db
            + "?sslmode=verify-full&sslrootcert=/migration/root.crt";
    try (GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(RUNTIME_IMAGE))
            .withNetwork(INTERNAL)
            .withLabel("hs-migration-test", "1")
            .withEnv(env)
            .withCopyFileToContainer(MountableFile.forHostPath(JAR, 0444), "/migration/app.jar")
            .withCopyToContainer(Transferable.of(tls.certificatePem(), 0444), "/migration/root.crt")
            .withCopyToContainer(secret(url), SECRET_DIR + "spring.datasource.url")
            .withCopyToContainer(secret(user), SECRET_DIR + "spring.datasource.username")
            .withCopyToContainer(secret(password), SECRET_DIR + "spring.datasource.password")
            .withCopyToContainer(secret(OWNER), SECRET_DIR + "app.migration.owner-role")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withUser("10002:10002").withEntrypoint("java"))
            .withCommand("-jar", "/migration/app.jar", "--migrate")
            .withStartupCheckStrategy(new UntilExited().withTimeout(Duration.ofMinutes(6)))) {
      container.start();
      Long exit =
          DockerClientFactory.instance()
              .client()
              .inspectContainerCmd(container.getContainerId())
              .exec()
              .getState()
              .getExitCodeLong();
      return new Cli(exit, container.getLogs());
    }
  }

  static Transferable secret(String value) {
    return Transferable.of(value.getBytes(StandardCharsets.UTF_8), 0444);
  }

  static final class UntilExited extends StartupCheckStrategy {
    @Override
    public StartupStatus checkStartupState(DockerClient client, String containerId) {
      return Boolean.TRUE.equals(getCurrentState(client, containerId).getRunning())
          ? StartupStatus.NOT_YET_KNOWN
          : StartupStatus.SUCCESSFUL;
    }
  }

  void assertFailsWithoutChanges(String db, String user, String owner) throws Exception {
    prepareDatabase(db);
    Throwable failure = catchRun(files(migrationUrl(db), user, PASSWORD, owner));
    report(db + " failure=" + chain(failure));
    assertThat(chainMessages(failure)).doesNotContain(PASSWORD);
    assertNothingCreated(db);
  }

  static void assertNothingCreated(String db) throws SQLException {
    assertThat(
            list(db, "SELECT nspname FROM pg_namespace WHERE nspname IN ('record','state','log')"))
        .isEmpty();
    assertThat(one(db, "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'none')"))
        .isEqualTo("none");
    assertThat(
            one(
                db,
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'"))
        .isEqualTo("0");
  }

  Throwable catchRun(Path files) {
    Throwable failure = catchRunOrNull(files);
    assertThat(failure).as("Migration이 실패해야 한다").isNotNull();
    return failure;
  }

  Throwable catchRunAt(Path files, String location) {
    try {
      MigrationMode.run(
          new String[] {"--migrate"}, new Properties(), new MigrationSettings(files), location);
    } catch (RuntimeException failure) {
      return failure;
    }
    throw new AssertionError("Migration이 실패해야 한다: " + location);
  }

  Throwable catchRunOrNull(Path files) {
    try {
      MigrationMode.run(new String[] {"--migrate"}, new Properties(), new MigrationSettings(files));
      return null;
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  void migrate(String db, String user, String owner) throws Exception {
    MigrationMode.run(
        new String[] {"--migrate"},
        new Properties(),
        new MigrationSettings(files(migrationUrl(db), user, PASSWORD, owner)));
  }

  Path files(String url, String user, String password, String owner) throws Exception {
    Path dir = Files.createTempDirectory(temp, "homestamp-migration");
    Files.writeString(dir.resolve("spring.datasource.url"), url);
    Files.writeString(dir.resolve("spring.datasource.username"), user);
    Files.writeString(dir.resolve("spring.datasource.password"), password);
    Files.writeString(dir.resolve("app.migration.owner-role"), owner);
    return dir;
  }

  /**
   * 관리자: DB 생성 → #185 {@code infra/db/bootstrap_roles.sql}(7fc6d1b)과 같은 역할·선행 권한 → 가짜 비밀번호 → 테스트 전용
   * DDL 감사 trigger. #185가 병합되면 이 SQL 대신 bootstrap 파일을 psql로 실행한다.
   */
  static void prepareDatabase(String db) throws Exception {
    admin(POSTGRES.getDatabaseName(), "CREATE DATABASE " + db);
    if (!rolesCreated) {
      try (Connection c = adminConnection(db);
          Statement s = c.createStatement()) {
        String attributes =
            " NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
        s.execute("CREATE ROLE " + OWNER + " NOLOGIN" + attributes);
        s.execute("CREATE ROLE " + MIGRATION + " LOGIN" + attributes);
        s.execute("CREATE ROLE " + APP + " LOGIN" + attributes);
        s.execute("CREATE ROLE " + PURGE + " LOGIN" + attributes);
        s.execute("CREATE ROLE " + HUMAN + " LOGIN" + attributes);
        s.execute(
            "GRANT " + OWNER + " TO " + MIGRATION + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
        s.execute("SET log_statement = 'none'");
        s.execute("ALTER ROLE " + MIGRATION + " PASSWORD '" + PASSWORD + "'");
        s.execute("ALTER ROLE " + APP + " PASSWORD '" + PASSWORD + "'");
      }
      rolesCreated = true;
    }
    admin(db, "GRANT CREATE ON DATABASE " + db + " TO " + OWNER);
    admin(db, "REVOKE TEMP ON DATABASE " + db + " FROM PUBLIC");
    admin(
        db,
        "GRANT CONNECT ON DATABASE "
            + db
            + " TO "
            + String.join(", ", MIGRATION, APP, PURGE, HUMAN));
    admin(db, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
    admin(db, "GRANT USAGE, CREATE ON SCHEMA public TO " + OWNER);
    admin(
        db,
        """
        CREATE SCHEMA hsaudit;
        CREATE FUNCTION hsaudit.log_ddl() RETURNS event_trigger LANGUAGE plpgsql AS $$
        DECLARE r record;
        BEGIN
          FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
            RAISE LOG 'hsddl|%|%|%|%', session_user, current_user, r.command_tag, r.object_identity;
          END LOOP;
        END $$;
        CREATE EVENT TRIGGER hs_ddl_audit ON ddl_command_end EXECUTE FUNCTION hsaudit.log_ddl();
        """);
  }

  /** 역할 계약: owner NOLOGIN·NOINHERIT, migration→owner 멤버십은 SET만. */
  static void roleContract(String db) throws SQLException {
    assertThat(
            one(
                db,
                "SELECT rolcanlogin||'|'||rolinherit||'|'||rolsuper FROM pg_roles WHERE rolname='hs_owner'"))
        .isEqualTo("false|false|false");
    assertThat(
            one(
                db,
                "SELECT rolcanlogin||'|'||rolinherit||'|'||rolsuper FROM pg_roles WHERE rolname='hs_migration'"))
        .isEqualTo("true|false|false");
    assertThat(
            list(
                db,
                """
                SELECT r.rolname||'->'||g.rolname||' set='||m.set_option||' inherit='||m.inherit_option||' admin='||m.admin_option
                FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member JOIN pg_roles g ON g.oid=m.roleid
                WHERE (r.rolname LIKE 'hs\\_%' OR g.rolname LIKE 'hs\\_%')
                ORDER BY 1\
                """))
        .contains("hs_migration->hs_owner set=true inherit=false admin=false")
        .noneMatch(row ->
            row.startsWith("hs_app->")
                || row.startsWith("hs_purge->")
                || row.startsWith("hs_human->"));
    assertThat(
            one(
                db,
                "SELECT pg_has_role('hs_migration','hs_owner','SET')||'|'||pg_has_role('hs_migration','hs_owner','USAGE')"))
        .isEqualTo("true|false");
    // App·Purge·Human: 실제로 있는 LOGIN·NOINHERIT 역할이고 Owner로 전환·상속하지 못한다(GRANT 범위는 #156에서 본다)
    assertThat(
            list(
                db,
                "SELECT rolname||'|'||rolcanlogin||'|'||rolinherit||'|'||pg_has_role(rolname,'hs_owner','SET')||'|'||pg_has_role(rolname,'hs_owner','USAGE')"
                    + " FROM pg_roles WHERE rolname IN ('hs_app','hs_purge','hs_human') ORDER BY 1"))
        .containsExactly(
            "hs_app|true|false|false|false",
            "hs_human|true|false|false|false",
            "hs_purge|true|false|false|false");
  }

  /** SET ROLE 없이 Migration 로그인으로 접속: 보호 객체 생성·변경·조회가 모두 막히고, SET ROLE은 된다. */
  static void loginWithoutSetRole(String db) throws SQLException {
    String table =
        one(
            db,
            "SELECT 'record.'||relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='record' AND relkind='r' ORDER BY relname LIMIT 1");
    try (Connection c = DriverManager.getConnection(url(db), MIGRATION, PASSWORD);
        Statement s = c.createStatement()) {
      c.setAutoCommit(true);
      assertThat(query(s, "SELECT session_user||'|'||current_user||'|'||current_role"))
          .isEqualTo("hs_migration|hs_migration|hs_migration");
      for (String sql :
          List.of(
              "CREATE SCHEMA hs_intrude",
              "CREATE TABLE public.hs_intrude(id int)",
              "SELECT 1 FROM " + table + " LIMIT 1",
              "ALTER TABLE " + table + " ADD COLUMN hs_intrude int",
              "DROP TABLE public.flyway_schema_history",
              "SELECT 1 FROM public.flyway_schema_history LIMIT 1")) {
        assertThatThrownBy(() -> s.execute(sql))
            .isInstanceOf(SQLException.class)
            .satisfies(e ->
                report(
                    "no_set_role denied sqlstate="
                        + ((SQLException) e).getSQLState()
                        + " for "
                        + sql.replaceAll("record\\.\\w+", "record.<table>")));
      }
      s.execute("SET ROLE hs_owner");
      report(
          "after SET ROLE "
              + query(s, "SELECT session_user||'|'||current_user||'|'||current_role"));
      s.execute("RESET ROLE");
    }
    try (Connection c = DriverManager.getConnection(url(db), APP, PASSWORD);
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("SET ROLE hs_owner")).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> s.execute("SELECT 1 FROM " + table + " LIMIT 1"))
          .isInstanceOf(SQLException.class);
      report(
          "app login: SET ROLE owner denied, record select denied (grants are applied in a separate step)");
    }
  }

  static void insertHistory(String db, String version, String description) throws SQLException {
    admin(
        db,
        "INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
            + "SELECT max(installed_rank)+1,'"
            + version
            + "','"
            + description
            + "','SQL','V"
            + version
            + "__test.sql',1,'hs_owner',0,true FROM public.flyway_schema_history");
  }

  static List<String> snapshot(String db) throws SQLException {
    return list(
        db,
        """
        SELECT kind||'|'||ident||'|'||owner FROM (
          SELECT 'schema' kind, nspname::text ident, nspowner::regrole::text owner FROM pg_namespace WHERE nspname IN %1$s
          UNION ALL SELECT 'rel_'||c.relkind::text, n.nspname||'.'||c.relname, c.relowner::regrole::text
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN %1$s
          UNION ALL SELECT 'proc', p.oid::regprocedure::text, p.proowner::regrole::text
            FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN %1$s
          UNION ALL SELECT 'type_'||t.typtype::text, n.nspname||'.'||t.typname, t.typowner::regrole::text
            FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname IN %1$s
        ) s ORDER BY 1\
        """
            .formatted(SCHEMAS));
  }

  static List<String> history(String db) throws SQLException {
    return list(
        db,
        "SELECT coalesce(version,'-')||'|'||description||'|'||coalesce(checksum::text,'-')||'|'||success||'|'||installed_by||'|'||type FROM public.flyway_schema_history ORDER BY installed_rank");
  }

  // 로그 파싱: prefix = user|db|host|
  static final Pattern LINE =
      Pattern.compile("^([^|\\n]*)\\|([^|\\n]*)\\|([^|\\n]*)\\|(\\w+):  (.*)$", Pattern.MULTILINE);

  static List<String[]> ddlAudit(String log, String db) {
    List<String[]> rows = new ArrayList<>();
    Matcher m = LINE.matcher(log);
    while (m.find()) {
      if (m.group(2).equals(db) && m.group(5).startsWith("hsddl|")) {
        String[] p = m.group(5).split("\\|", 5);
        rows.add(new String[] {p[1], p[2], p[3], p.length > 4 ? p[4] : ""});
      }
    }
    return rows;
  }

  static List<String> statements(String log, String db, String user) {
    List<String> out = new ArrayList<>();
    Matcher m = LINE.matcher(log);
    while (m.find()) {
      if (!m.group(1).equals(user) || !m.group(2).equals(db) || !m.group(4).equals("LOG")) continue;
      String body = m.group(5);
      if (body.startsWith("statement: ")) out.add(body.substring(11));
      else if (body.startsWith("execute ")) out.add(body.substring(body.indexOf(": ") + 2));
    }
    return out;
  }

  static Map<String, Integer> statementVerbs(String log, String db, String user) {
    Map<String, Integer> verbs = new TreeMap<>();
    for (String s : statements(log, db, user)) {
      String[] words = s.trim().split("\\s+", 3);
      String verb = words[0].toUpperCase();
      if (Set.of("CREATE", "ALTER", "SET", "DROP", "COMMENT").contains(verb) && words.length > 1)
        verb += " " + words[1].toUpperCase();
      verbs.merge(verb, 1, Integer::sum);
    }
    return verbs;
  }

  static Set<String> users(String log, String db) {
    Set<String> users = new TreeSet<>();
    Matcher m = LINE.matcher(log);
    while (m.find()) if (m.group(2).equals(db) && !m.group(1).isEmpty()) users.add(m.group(1));
    return users;
  }

  static Set<String> hosts(String log, String db) {
    Set<String> hosts = new TreeSet<>();
    Matcher m = LINE.matcher(log);
    while (m.find()) if (m.group(2).equals(db)) hosts.add(m.group(3));
    return hosts;
  }

  static Set<String> otherDbUsers(String log, String db) {
    Set<String> out = new TreeSet<>();
    Matcher m = LINE.matcher(log);
    while (m.find())
      if (!m.group(2).isEmpty() && !m.group(2).equals(db)) out.add(m.group(1) + "@" + m.group(2));
    return out;
  }

  static int indexOf(List<String> rows, String regex) {
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).matches(regex)) return i;
    return -1;
  }

  static String pairs(List<String[]> rows) {
    Set<String> s = new TreeSet<>();
    rows.forEach(r -> s.add("session=" + r[0] + ",current=" + r[1]));
    return s.toString();
  }

  static String tags(List<String[]> rows) {
    Map<String, Integer> t = new TreeMap<>();
    rows.forEach(r -> t.merge(r[2], 1, Integer::sum));
    return t.toString();
  }

  static String chain(Throwable failure) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      out.append(t.getClass().getSimpleName());
      if (t instanceof SQLException sql)
        out.append("(sqlstate=").append(sql.getSQLState()).append(")");
      out.append(" > ");
    }
    return out.toString();
  }

  static String chainMessages(Throwable failure) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause())
      out.append(t.getMessage()).append('\n');
    return out.toString();
  }

  static void report(String line) {
    System.out.println("MIGRATION| " + line);
  }

  /** Migration 파일의 주소: 운영 계약과 같이 verify-full과 신뢰 루트를 요구한다. */
  static String migrationUrl(String db) {
    return url(db) + "?sslmode=verify-full&sslrootcert=" + tls.rootCertificate();
  }

  static String url(String db) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(5432)
        + "/"
        + db;
  }

  static Connection adminConnection(String db) throws SQLException {
    return DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  static void admin(String db, String sql) throws SQLException {
    try (Connection c = adminConnection(db);
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  static String one(String db, String sql) throws SQLException {
    List<String> rows = list(db, sql);
    return rows.isEmpty() ? null : rows.get(0);
  }

  static List<String> list(String db, String sql) throws SQLException {
    try (Connection c = adminConnection(db);
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      List<String> rows = new ArrayList<>();
      while (r.next()) rows.add(r.getString(1));
      return rows;
    }
  }

  static String query(Statement s, String sql) throws SQLException {
    try (ResultSet r = s.executeQuery(sql)) {
      r.next();
      return r.getString(1);
    }
  }

  /** JDK keytool로 만든 자체 서명 인증서(SAN: localhost·hs-pg·Docker 호스트). 신뢰 루트이자 서버 인증서로 쓴다. */
  record TestTls(Path rootCertificate, String certificatePem, String privateKeyPem) {

    static TestTls generate(Path directory, List<String> names) throws Exception {
      Files.createDirectories(directory);
      Path keystore = directory.resolve("server.p12");
      String password = "test-keystore-" + System.nanoTime();
      String san =
          String.join(
              ",",
              new TreeSet<>(names)
                  .stream()
                      .map(name -> name.matches("[0-9.]+") ? "ip:" + name : "dns:" + name)
                      .toList());
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                  "-genkeypair",
                  "-alias",
                  "pg",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=homestamp-migration-test",
                  "-ext",
                  "SAN=" + san + ",ip:127.0.0.1",
                  "-keystore",
                  keystore.toString(),
                  "-storetype",
                  "PKCS12",
                  "-storepass",
                  password,
                  "-keypass",
                  password,
                  "-noprompt")
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as("keytool: %s", output).isZero();
      java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
      try (var in = Files.newInputStream(keystore)) {
        store.load(in, password.toCharArray());
      }
      java.util.Base64.Encoder pem =
          java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
      String certificate =
          "-----BEGIN CERTIFICATE-----\n"
              + pem.encodeToString(store.getCertificate("pg").getEncoded())
              + "\n-----END CERTIFICATE-----\n";
      String key =
          "-----BEGIN PRIVATE KEY-----\n"
              + pem.encodeToString(store.getKey("pg", password.toCharArray()).getEncoded())
              + "\n-----END PRIVATE KEY-----\n";
      Path root = Files.writeString(directory.resolve("root.crt"), certificate);
      Files.delete(keystore);
      return new TestTls(root.toAbsolutePath(), certificate, key);
    }

    @Override
    public String toString() {
      return "TestTls[" + rootCertificate + "]";
    }
  }
}
