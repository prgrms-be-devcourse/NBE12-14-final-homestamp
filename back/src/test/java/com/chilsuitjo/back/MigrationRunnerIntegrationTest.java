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
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
// 운영 Migration은 Linux 컨테이너에서만 돈다. 설정 계약(sslrootcert는 POSIX 절대 경로, 파일 권한·심볼릭 링크·FIFO 거절)을
// 그대로 시험하므로 Windows에서는 건너뛴다(CI는 Linux에서 모두 돈다, #173 리뷰)
@EnabledOnOs(
    value = {OS.LINUX, OS.MAC},
    disabledReason = "운영 Migration 설정은 POSIX 경로·권한 계약이다(Linux 컨테이너 전용)")
class MigrationRunnerIntegrationTest {

  static final String OWNER = "hs_owner";
  static final String MIGRATION = "hs_migration";
  // 이 시험의 접속용 로그인 역할. 이미지 roles.properties의 운영 이름(G_*)과 겹치지 않게 둔다
  static final String APP = "hs_login_app";
  static final String PURGE = "hs_login_purge";
  static final String HUMAN = "hs_login_human";
  static final String PASSWORD = "MIGRATION-RUNNER-FAKE-PASSWORD";
  static final String SCHEMAS = "('public','record','state','log')";
  static final Path JAR =
      Path.of(
          System.getProperty(
              "migration.test.production-boot-jar", "build/libs/back-0.0.1-SNAPSHOT.jar"));
  // 운영 Dockerfile 런타임과 같은 base 이미지(UID 10002로 실행)
  static final String RUNTIME_IMAGE = "eclipse-temurin:25-jre-noble";
  static final String SECRET_DIR = "/run/secrets/homestamp-migration/";
  // 기대 이력은 운영 Migration 폴더(db/migration)의 V 파일에서 구한다. 새 V 파일이 생겨도 이 테스트를 고치지 않는다
  static final List<String> MIGRATION_VERSIONS = migrationVersions();
  // 이미지 db/grants/roles.properties의 운영 이름과 같다(decisions 2026-11-28)
  static final String G_APP = "hs_app";
  static final String G_PURGE = "hs_purge";
  // Human은 NOLOGIN 읽기 그룹(#185). 사람 계정은 그 그룹의 멤버로 만든다
  static final String G_HUMAN = "hs_human_read";
  static final String G_PERSON = "homestamp_person_a";
  static final List<String> GRANT_LOGINS = List.of(G_APP, G_PURGE);

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
  void 정상_실행은_owner로_전환해_모든_V_파일을_적용하고_재실행은_아무것도_바꾸지_않는다() throws Exception {
    String db = "hs_main";
    prepareDatabase(db);
    roleContract(db);
    int logStart = logMark();

    migrate(db, MIGRATION, OWNER);

    String log = logSince(logStart);
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
    assertThat(history).hasSize(MIGRATION_VERSIONS.size());
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
    int rerunStart = logMark();
    migrate(db, MIGRATION, OWNER);
    String rerunLog = logSince(rerunStart);
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

    // 미래 버전: 이 이미지의 마지막 V 파일 다음 번호와 먼 번호(실제 V 파일과 겹치지 않게 폴더에서 구한다)
    String next = String.valueOf(Integer.parseInt(MIGRATION_VERSIONS.getLast()) + 1);
    for (String future : List.of(next, "9999")) {
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
    int logStart = logMark();
    Throwable failure = catchRun(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER));
    String log = logSince(logStart);
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
    int logStart = logMark();
    Throwable failure =
        catchRunAt(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER),
            "classpath:migration-test/badname");
    String log = logSince(logStart);
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
    assertThat(first.logs()).as("JDBC 주소를 로그에 남기지 않는다").doesNotContain("jdbc:postgresql");
    assertThat(history(db)).hasSize(MIGRATION_VERSIONS.size());

    Map<String, String> attack = new LinkedHashMap<>();
    attack.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("SPRING_DATASOURCE_HIKARI_JDBC_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("SPRING_DATASOURCE_USERNAME", "hs_attacker");
    attack.put("SPRING_APPLICATION_JSON", "{\"spring.datasource.username\":\"hs_attacker\"}");
    attack.put("FLYWAY_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("FLYWAY_LOCATIONS", "filesystem:/tmp");
    attack.put("PGHOST", "hs-attacker");
    int logStart = logMark();
    Cli rerun = cli(db, MIGRATION, PASSWORD, attack);
    String log = logSince(logStart);
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
    assertThat(history(db)).hasSize(MIGRATION_VERSIONS.size());

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
    // prod와 local·test를 함께 켠 조합은 #216 ProfileGuard가 시작부터 막아 실제로 생기지 않는다(ProfileGuard 테스트 몫).
    // 실제 가드는 spring.flyway.enabled=false 단언이다. 테스트 DB에 TLS가 없어 Flyway가 켜지면 시작이 실패하므로,
    // 아래 "이력 테이블 없음"은 보조 확인이다
    for (String profiles : List.of("prod")) {
      String db = "hs_app_" + profiles.replace(",", "_");
      admin(POSTGRES.getDatabaseName(), "CREATE DATABASE " + db);
      assertFlywayProfile(db, profiles, false);
      assertThat(
              one(db, "SELECT coalesce(to_regclass('public.flyway_schema_history')::text,'none')"))
          .isEqualTo("none");
    }
  }

  @Test
  void local과_test_프로필은_Flyway를_켜고_모든_V_파일을_적용한다() throws Exception {
    for (String profiles : List.of("local", "test")) {
      String db = "hs_app_" + profiles;
      admin(POSTGRES.getDatabaseName(), "CREATE DATABASE " + db);
      assertFlywayProfile(db, profiles, true);
      assertThat(
              list(
                  db,
                  "SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
          .containsExactlyElementsOf(MIGRATION_VERSIONS);
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
        prodParameters(db, POSTGRES.getUsername(), POSTGRES.getPassword()));
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

  /**
   * #165의 운영 SSM 설정 12개(테스트 {@code config/application.yml}과 맞춘 값)에서 DB 접속만 이 DB로 바꾼다. 운영 주소 계약상
   * {@code sslmode=verify-full}과 절대 경로 인증서가 있어야 시작한다. 테스트 DB는 TLS가 없으므로, 운영 앱이 실수로 Flyway를 켜고 붙으면
   * 시작이 실패해 이 테스트가 깨진다(꺼져 있으면 연결하지 않는다).
   */
  static List<SsmConfigParameter> prodParameters(String db, String username, String password) {
    String prefix = "/homestamp/prod/app/spring.datasource.";
    Map<String, String> database =
        Map.of(
            prefix + "url",
            url(db) + "?sslmode=verify-full&sslrootcert=" + SsmTestParameterSource.ROOT_CERT,
            prefix + "username",
            username,
            prefix + "password",
            password);
    return SsmTestParameterSource.validParameters().stream()
        .map(parameter ->
            database.containsKey(parameter.name())
                ? new SsmConfigParameter(
                    parameter.name(), database.get(parameter.name()), parameter.type())
                : parameter)
        .toList();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
  static class FlywayOnlyConfiguration {}

  // ---------------------------------------------------------------- --apply-grants (in-process)

  @Test
  void apply_grants는_검증만_하고_migrate없이_한_트랜잭션으로_GRANT하며_재실행은_같다() throws Exception {
    String db = "hs_g_main";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    List<String> objects = snapshot(db);
    List<String> historyBefore = history(db);
    int logStart = logMark();

    applyGrants(db);

    String log = logSince(logStart);
    List<String> executed = statements(log, db, MIGRATION);
    report("apply_grants verbs=" + statementVerbs(log, db, MIGRATION));
    // migrate가 아니다: 스키마 객체·이력은 그대로, DDL은 GRANT·REVOKE·ALTER DEFAULT PRIVILEGES(DO 블록 안)만
    assertThat(snapshot(db)).isEqualTo(objects);
    assertThat(history(db)).isEqualTo(historyBefore);
    assertThat(ddlAudit(log, db))
        .allSatisfy(row -> {
          assertThat(row[0] + "/" + row[1]).isEqualTo(MIGRATION + "/" + OWNER);
          assertThat(row[2]).isIn("GRANT", "REVOKE", "ALTER DEFAULT PRIVILEGES");
        });
    assertThat(executed).anyMatch(s -> s.startsWith("-- HomeStamp DB grants"));
    assertThat(executed)
        .noneMatch(s -> s.matches("(?is)\\s*(CREATE|ALTER TABLE|INSERT|UPDATE|DELETE).*"));
    // 실제 권한: 이미지의 역할 설정 이름에 #156 정책이 들어갔다
    assertThat(
            one(
                db,
                "SELECT has_table_privilege('"
                    + G_APP
                    + "','record.action','INSERT')||'|'"
                    + "||has_table_privilege('"
                    + G_APP
                    + "','record.action','UPDATE')||'|'"
                    + "||has_function_privilege('"
                    + G_PURGE
                    + "','state.purge_room_record(uuid)','EXECUTE')||'|'"
                    + "||has_column_privilege('"
                    + G_HUMAN
                    + "','state.member','status','SELECT')||'|'"
                    + "||has_column_privilege('"
                    + G_HUMAN
                    + "','state.member','password_hash','SELECT')||'|'"
                    + "||has_column_privilege('"
                    + G_HUMAN
                    + "','record.cause_note','expert_name','SELECT')"))
        .isEqualTo("true|false|true|true|false|false");
    List<String> acl = acl(db);
    applyGrants(db);
    assertThat(acl(db)).as("재실행 결과가 같다").isEqualTo(acl);
    loginWithoutSetRole(db);
  }

  @Test
  void Human은_읽기_그룹에만_권한을_받고_사람_계정은_그룹으로_같은_최소_권한을_쓴다() throws Exception {
    String db = "hs_g_human_group";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    MigrationMode.run(
        new String[] {"--apply-grants"},
        new Properties(),
        new MigrationSettings(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER)));
    for (String role : List.of(G_HUMAN, G_PERSON)) {
      assertThat(
              one(
                  db,
                  "SELECT has_column_privilege('"
                      + role
                      + "','state.member','status','SELECT')||'|'"
                      + "||has_column_privilege('"
                      + role
                      + "','state.member','phone','SELECT')||'|'"
                      + "||has_table_privilege('"
                      + role
                      + "','record.action','SELECT')||'|'"
                      + "||has_table_privilege('"
                      + role
                      + "','record.action','INSERT')"))
          .as(role)
          .isEqualTo("true|false|true|false");
    }
    // 사람 계정에는 직접 권한이 없다(그룹 멤버십으로만 읽는다)
    assertThat(
            one(
                db,
                "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = '"
                    + G_PERSON
                    + "'"))
        .isEqualTo("0");
  }

  @Test
  void 이력이_이미지와_다르면_GRANT_SQL을_실행하지_않고_권한을_바꾸지_않는다() throws Exception {
    String db = "hs_g_drift";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    applyGrants(db);
    List<String> acl = acl(db);
    admin(db, "REVOKE INSERT ON record.action FROM " + G_APP); // GRANT가 돌면 되돌아오는 표시
    List<String> marked = acl(db);
    assertThat(marked).isNotEqualTo(acl);

    // 대기 중인 Migration: DB는 V7까지(V8 이력 삭제), 이미지는 V8까지
    String v8 =
        one(
            db,
            "SELECT installed_rank||','||version||','||description||','||type||','||script||','||checksum FROM public.flyway_schema_history WHERE version='8'");
    admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '8'");
    assertGrantRejected(
        db, "pending_V8", "Detected resolved migration not applied to database: 8", marked);
    String[] row = v8.split(",");
    admin(
        db,
        "INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES ("
            + row[0]
            + ",'"
            + row[1]
            + "','"
            + row[2]
            + "','"
            + row[3]
            + "','"
            + row[4]
            + "',"
            + row[5]
            + ",'hs_owner',0,true)");

    admin(db, historyRow("99", "test future"));
    assertGrantRejected(db, "future_V99", ": 99", marked);
    admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '99'");

    admin(
        db, "UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '3'");
    assertGrantRejected(
        db, "checksum_V3", "Migration checksum mismatch for migration version 3", marked);
    admin(
        db, "UPDATE public.flyway_schema_history SET checksum = checksum - 1 WHERE version = '3'");

    admin(db, historyRow("4.1", "test missing locally"));
    assertGrantRejected(db, "missing_applied_4.1", ": 4.1", marked);
    admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '4.1'");

    admin(db, "UPDATE public.flyway_schema_history SET success = false WHERE version = '8'");
    assertGrantRejected(db, "failed_V8", "", marked);
    admin(db, "UPDATE public.flyway_schema_history SET success = true WHERE version = '8'");

    // 이력을 되돌리면 다시 성공하고 표시도 되돌아온다(검증이 하드코딩된 V8이 아니라 이미지 전체와의 비교임)
    applyGrants(db);
    assertThat(acl(db)).isEqualTo(acl);
  }

  void assertGrantRejected(String db, String name, String expectedDetail, List<String> acl)
      throws Exception {
    int logStart = logMark();
    Throwable failure =
        catchApply(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER), MigrationMode.MIGRATION_LOCATION);
    String log = logSince(logStart);
    String described = MigrationMode.describe((RuntimeException) failure);
    report(name + " described=" + described + " verbs=" + statementVerbs(log, db, MIGRATION));
    assertThat(failure).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
    assertThat(described)
        .contains("Flyway 검증 실패", expectedDetail)
        .doesNotContain(PASSWORD, "jdbc:");
    assertThat(statements(log, db, MIGRATION))
        .noneMatch(s -> s.startsWith("-- HomeStamp DB grants"));
    // 직전 관리자 REVOKE(표시용 DDL)의 로그가 늦게 흘러들 수 있어 Migration 로그인의 DDL만 본다
    assertThat(ddlAudit(log, db, MIGRATION)).isEmpty();
    assertThat(acl(db)).isEqualTo(acl);
  }

  @Test
  void Migration이_하나도_적용되지_않은_DB나_위치_문제면_GRANT하지_않는다() throws Exception {
    String db = "hs_g_fresh";
    prepareDatabase(db);
    List<String> acl = acl(db);
    Path files = files(migrationUrl(db), MIGRATION, PASSWORD, OWNER);
    Throwable fresh = catchApply(files, MigrationMode.MIGRATION_LOCATION);
    report(db + " fresh=" + MigrationMode.describe((RuntimeException) fresh));
    assertThat(fresh).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
    assertThat(MigrationMode.describe((RuntimeException) fresh)).contains(": 1");
    assertThat(catchApply(files, "classpath:migration-test/missing"))
        .satisfies(f ->
            assertThat(MigrationMode.describe((RuntimeException) f))
                .contains("migration-test/missing"));
    assertThat(catchApply(files, "classpath:migration-test/empty"))
        .hasMessage("적용하거나 검증할 Migration이 하나도 없다");
    assertNothingCreated(db);
    assertThat(acl(db)).isEqualTo(acl);

    // 이력은 있는데 위치가 비어 있으면: 적용된 버전이 이미지에 없으므로 검증 실패
    String applied = "hs_g_emptyloc";
    prepareDatabase(applied);
    migrate(applied, MIGRATION, OWNER);
    List<String> appliedAcl = acl(applied);
    Throwable empty =
        catchApply(
            files(migrationUrl(applied), MIGRATION, PASSWORD, OWNER),
            "classpath:migration-test/empty");
    report(applied + " empty_location=" + MigrationMode.describe((RuntimeException) empty));
    assertThat(empty).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
    assertThat(acl(applied)).isEqualTo(appliedAcl);
  }

  @Test
  void GRANT_SQL_사전_검사나_중간_오류는_종료_전에_전부_되돌리고_안전한_정보만_남긴다() throws Exception {
    String db = "hs_g_fail";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    applyGrants(db);
    admin(db, "REVOKE INSERT ON record.action FROM " + G_APP);
    List<String> before = acl(db);

    // 1) 사전 검사: 분류되지 않은 새 테이블(Migration 없이 owner가 만든 것처럼) → P0001
    admin(
        db,
        "CREATE TABLE state.hs_unclassified (id int); ALTER TABLE state.hs_unclassified OWNER TO "
            + OWNER);
    List<String> withTable = acl(db);
    Throwable precheck =
        catchApply(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER), MigrationMode.MIGRATION_LOCATION);
    String described = MigrationMode.describe((RuntimeException) precheck);
    report(db + " precheck=" + described);
    assertThat(described)
        .startsWith("GRANT SQL 실패(전체 ROLLBACK) sqlstate=P0001 message=")
        .contains("unclassified")
        .doesNotContain(PASSWORD, "jdbc:", "DO $$", "Where", "PL/pgSQL");
    assertThat(acl(db)).isEqualTo(withTable);
    admin(db, "DROP TABLE state.hs_unclassified");
    assertThat(acl(db)).isEqualTo(before);

    // 2) 권한 변경 중간 오류: 관리자 event trigger가 N번째 권한 변경에서 실패(테스트 전용) → 앞 변경까지 전부 ROLLBACK
    admin(
        db,
        "CREATE SCHEMA hsfault; CREATE TABLE hsfault.counter (seen int NOT NULL, fail_at int NOT NULL);"
            + " INSERT INTO hsfault.counter VALUES (0, 25);"
            + " CREATE FUNCTION hsfault.count() RETURNS event_trigger LANGUAGE plpgsql SECURITY DEFINER"
            + " SET search_path = pg_catalog AS $$ DECLARE n int; f int; BEGIN"
            + " UPDATE hsfault.counter SET seen = seen + 1 RETURNING seen, fail_at INTO n, f;"
            + " IF n = f THEN RAISE EXCEPTION 'injected failure at privilege change %', n; END IF; END $$;"
            + " CREATE EVENT TRIGGER hsfault_change ON ddl_command_end"
            + " WHEN TAG IN ('GRANT', 'REVOKE', 'ALTER DEFAULT PRIVILEGES') EXECUTE FUNCTION hsfault.count()");
    Throwable middle =
        catchApply(
            files(migrationUrl(db), MIGRATION, PASSWORD, OWNER), MigrationMode.MIGRATION_LOCATION);
    report(db + " middle=" + MigrationMode.describe((RuntimeException) middle));
    assertThat(MigrationMode.describe((RuntimeException) middle))
        .contains("sqlstate=P0001", "injected failure at privilege change 25");
    assertThat(one(db, "SELECT seen FROM hsfault.counter")).isEqualTo("0");
    admin(db, "DROP EVENT TRIGGER hsfault_change; DROP SCHEMA hsfault CASCADE");
    assertThat(acl(db)).as("앞의 24개 변경까지 되돌아갔다").isEqualTo(before);

    // 3) 검증과 GRANT 사이에 이력이 바뀌면(다른 배포) GRANT 트랜잭션 안에서 다시 확인해 실패
    try (Connection c = DriverManager.getConnection(migrationUrl(db), MIGRATION, PASSWORD)) {
      assertThatThrownBy(() ->
              com.chilsuitjo.back.global.config.migration.DatabaseGrants.apply(
                  c,
                  new com.chilsuitjo.back.global.config.migration.GrantRoles(
                      OWNER, G_APP, G_PURGE, G_HUMAN),
                  "1:1:stale"))
          .hasMessageContaining("Flyway 검증 뒤 Migration 이력이 바뀌었다");
      assertThat(c.getAutoCommit()).isTrue();
    }
    assertThat(acl(db)).isEqualTo(before);
  }

  @Test
  void 역할_설정이_잘못되면_DB에_닿기_전에_실패한다() throws Exception {
    String db = "hs_g_roles";
    prepareDatabase(db);
    migrate(db, MIGRATION, OWNER);
    List<String> acl = acl(db);
    Path files = files(migrationUrl(db), MIGRATION, PASSWORD, OWNER);
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("grants-roles-test/bad-name.properties", "GRANT 역할 이름 형식이 올바르지 않다");
    cases.put("grants-roles-test/unknown-key.properties", "GRANT 역할 설정에 모르는 키가 있다: owner-role");
    cases.put("grants-roles-test/duplicate-key.properties", "GRANT 역할 설정 키가 중복됐다: app-role");
    cases.put("grants-roles-test/missing-key.properties", "GRANT 역할 설정이 없다: purge-role");
    cases.put("grants-roles-test/duplicate-name.properties", "GRANT 역할 이름이 겹친다");
    cases.put("grants-roles-test/migration-login.properties", "GRANT 대상에 Migration 로그인을 넣지 않는다");
    cases.put("grants-roles-test/empty-human.properties", "GRANT 역할 이름 형식이 올바르지 않다");
    cases.put("grants-roles-test/no-such.properties", "GRANT 역할 설정 리소스가 없다");
    int logStart = logMark();
    for (Map.Entry<String, String> c : cases.entrySet()) {
      Throwable failure = null;
      try {
        MigrationMode.run(
            new String[] {"--apply-grants"},
            new Properties(),
            new MigrationSettings(files),
            MigrationMode.MIGRATION_LOCATION,
            c.getKey());
      } catch (RuntimeException e) {
        failure = e;
      }
      assertThat(failure).as(c.getKey()).isNotNull();
      assertThat(MigrationMode.describe((RuntimeException) failure))
          .as(c.getKey())
          .isEqualTo(c.getValue());
    }
    assertThat(users(logSince(logStart), db)).as("DB에 접속하지 않았다").isEmpty();
    // Owner 이름이 역할 대상과 겹쳐도 실패
    assertThatThrownBy(() ->
            MigrationMode.run(
                new String[] {"--apply-grants"},
                new Properties(),
                new MigrationSettings(files(migrationUrl(db), MIGRATION, PASSWORD, G_APP))))
        .hasMessage("GRANT 역할 이름이 겹친다");
    assertThat(acl(db)).isEqualTo(acl);
  }

  void applyGrants(String db) throws Exception {
    MigrationMode.run(
        new String[] {"--apply-grants"},
        new Properties(),
        new MigrationSettings(files(migrationUrl(db), MIGRATION, PASSWORD, OWNER)));
  }

  Throwable catchApply(Path files, String location) {
    try {
      MigrationMode.run(
          new String[] {"--apply-grants"},
          new Properties(),
          new MigrationSettings(files),
          location);
    } catch (RuntimeException failure) {
      return failure;
    }
    throw new AssertionError("GRANT가 실패해야 한다: " + location);
  }

  /** 권한 스냅샷: 4개 스키마의 테이블·컬럼·함수·스키마 ACL과 모든 기본 권한. */
  static List<String> acl(String db) throws SQLException {
    return list(
        db,
        "SELECT x FROM ("
            + "SELECT 'rel '||c.oid::regclass||' '||coalesce(c.relacl::text,'-') x FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
            + " WHERE n.nspname IN "
            + SCHEMAS
            + " AND c.relkind IN ('r','p','S','v','m')"
            + " UNION ALL SELECT 'col '||a.attrelid::regclass||'.'||a.attname||' '||a.attacl::text FROM pg_attribute a"
            + " JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace"
            + " WHERE n.nspname IN "
            + SCHEMAS
            + " AND a.attnum>0 AND NOT a.attisdropped AND a.attacl IS NOT NULL"
            + " UNION ALL SELECT 'fn '||p.oid::regprocedure||' '||coalesce(p.proacl::text,'-') FROM pg_proc p"
            + " JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN "
            + SCHEMAS
            + " UNION ALL SELECT 'nsp '||nspname||' '||coalesce(nspacl::text,'-') FROM pg_namespace WHERE nspname IN "
            + SCHEMAS
            + " UNION ALL SELECT 'def '||defaclrole::regrole||' '||defaclnamespace||' '||defaclobjtype::text||' '||defaclacl::text FROM pg_default_acl"
            + ") s ORDER BY x");
  }

  // ---------------------------------------------------------------- 실제 --apply-grants CLI (UID
  // 10002, TLS)

  @Test
  void 실제_CLI의_apply_grants는_UID_10002로_성공_0_실패_1이고_환경변수로_대상을_바꾸지_못한다() throws Exception {
    assumeTrue(Files.isRegularFile(JAR), "bootJar 먼저 빌드");
    String db = "hs_g_cli";
    prepareDatabase(db);
    assertThat(cli(db, MIGRATION, PASSWORD, Map.of()).exit()).isZero(); // --migrate

    Cli first = cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--apply-grants");
    report(
        "cli apply_grants exit="
            + first.exit()
            + " granted="
            + first.logs().contains("GRANT를 적용했다"));
    assertThat(first.exit()).isZero();
    assertThat(first.logs())
        .contains("GRANT를 적용했다")
        .doesNotContain(PASSWORD, " DEBUG ", "jdbc:postgresql");
    List<String> acl = acl(db);

    Map<String, String> attack = new LinkedHashMap<>();
    attack.put("HOMESTAMP_GRANTS_APP_ROLE", APP);
    attack.put("APP_ROLE", APP);
    attack.put(
        "SPRING_APPLICATION_JSON", "{\"app-role\":\"" + APP + "\",\"human-role\":\"" + APP + "\"}");
    attack.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://hs-attacker:5432/x");
    attack.put("FLYWAY_LOCATIONS", "filesystem:/tmp");
    Cli rerun = cli(db, MIGRATION, PASSWORD, attack, OWNER, "--apply-grants");
    assertThat(rerun.exit()).isZero();
    assertThat(acl(db)).as("재실행·공격 환경변수에도 같은 결과").isEqualTo(acl);
    assertThat(one(db, "SELECT has_table_privilege('" + APP + "','record.action','SELECT')"))
        .isEqualTo("f");

    Map<String, Cli> failures = new LinkedHashMap<>();
    failures.put(
        "wrong_password",
        cli(db, MIGRATION, "wrong-" + PASSWORD.length(), Map.of(), OWNER, "--apply-grants"));
    failures.put(
        "bad_owner_name", cli(db, MIGRATION, PASSWORD, Map.of(), "Hs-Owner", "--apply-grants"));
    failures.put(
        "both_arguments",
        cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--migrate", "--apply-grants"));
    failures.put(
        "extra_argument",
        cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--apply-grants", "--debug"));
    failures.put(
        "app_profile",
        cli(
            db,
            MIGRATION,
            PASSWORD,
            Map.of("SPRING_PROFILES_ACTIVE", "prod"),
            OWNER,
            "--apply-grants"));
    admin(db, historyRow("99", "test future"));
    failures.put("future_V99", cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--apply-grants"));
    admin(db, "DELETE FROM public.flyway_schema_history WHERE version = '99'");
    admin(db, "UPDATE public.flyway_schema_history SET version = '8.0.0.1' WHERE version = '8'");
    failures.put("pending_V8", cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--apply-grants"));
    admin(db, "UPDATE public.flyway_schema_history SET version = '8' WHERE version = '8.0.0.1'");
    admin(
        db,
        "CREATE TABLE state.hs_unclassified (id int); ALTER TABLE state.hs_unclassified OWNER TO "
            + OWNER);
    List<String> withTable = acl(db);
    failures.put(
        "precheck_unclassified", cli(db, MIGRATION, PASSWORD, Map.of(), OWNER, "--apply-grants"));
    assertThat(acl(db)).isEqualTo(withTable);
    admin(db, "DROP TABLE state.hs_unclassified");
    for (Map.Entry<String, Cli> f : failures.entrySet()) {
      Matcher m = Pattern.compile("GRANT를 실행하지 못했다: [^\\n]*").matcher(f.getValue().logs());
      report(
          "cli "
              + f.getKey()
              + " exit="
              + f.getValue().exit()
              + " reason=["
              + (m.find() ? m.group() : "-")
              + "]");
      assertThat(f.getValue().exit()).as(f.getKey()).isEqualTo(1);
      assertThat(f.getValue().logs())
          .as(f.getKey())
          .doesNotContain(
              PASSWORD,
              "hs-attacker",
              "DO $$",
              "-- HomeStamp DB grants",
              "jdbc:postgresql"); // Flyway 접속 주소 줄도 남기지 않는다
    }
    assertThat(acl(db)).as("실패한 실행은 권한을 바꾸지 않았다").isEqualTo(acl);
  }

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
    return cli(db, user, password, env, OWNER, "--migrate");
  }

  static Cli cli(
      String db,
      String user,
      String password,
      Map<String, String> env,
      String owner,
      String... arguments) {
    List<String> command = new ArrayList<>(List.of("-jar", "/migration/app.jar"));
    command.addAll(List.of(arguments));
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
            .withCopyToContainer(secret(owner), SECRET_DIR + "app.migration.owner-role")
            .withCreateContainerCmdModifier(
                cmd -> cmd.withUser("10002:10002").withEntrypoint("java"))
            .withCommand(command.toArray(String[]::new))
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
        // --apply-grants 대상: 이미지의 db/grants/roles.properties 이름. Human 그룹과 그 멤버인 사람 계정 하나
        for (String role : GRANT_LOGINS) {
          s.execute("CREATE ROLE " + role + " LOGIN" + attributes);
        }
        s.execute("CREATE ROLE " + G_HUMAN + " NOLOGIN" + attributes);
        s.execute("CREATE ROLE " + G_PERSON + " LOGIN" + attributes);
        s.execute(
            "GRANT " + G_HUMAN + " TO " + G_PERSON + " WITH ADMIN FALSE, INHERIT TRUE, SET FALSE");
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
            row.startsWith(APP + "->")
                || row.startsWith(PURGE + "->")
                || row.startsWith(HUMAN + "->"));
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
                    + " FROM pg_roles WHERE rolname IN ('"
                    + String.join("','", APP, PURGE, HUMAN)
                    + "') ORDER BY 1"))
        .containsExactly(
            APP + "|true|false|false|false",
            HUMAN + "|true|false|false|false",
            PURGE + "|true|false|false|false");
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

  static List<String[]> ddlAudit(String log, String db, String user) {
    List<String[]> rows = new ArrayList<>();
    Matcher m = LINE.matcher(log);
    while (m.find()) {
      if (m.group(1).equals(user) && m.group(2).equals(db) && m.group(5).startsWith("hsddl|")) {
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

  /**
   * 여기부터가 새 로그인 위치({@link #logSince}와 짝). 컨테이너 로그는 Docker를 거쳐 늦게 들어오므로, 길이만 재면 앞서 끝난 접속(예: 직전
   * {@code --apply-grants}의 GRANT)의 로그가 그 뒤에 섞여 들어와 이번 실행의 것으로 읽힌다(#353). 관리자 접속으로 표시 문장을 남기고 그것이
   * 로그에 보일 때까지 기다린 뒤 그 줄 다음을 돌려준다. 로그는 문장을 실행한 순서대로 쌓이므로, 표시가 보이면 그 앞의 로그는 모두 들어와 있다. 표시는 테스트 DB가
   * 아닌 기본 DB에 남겨 DB별 로그 검사에 섞이지 않는다.
   */
  static int logMark() throws SQLException {
    String logs = awaitMark();
    int end = logs.indexOf('\n', logs.lastIndexOf(MARK));
    return end < 0 ? logs.length() : end + 1;
  }

  /** {@link #logMark} 뒤의 로그. 끝에도 표시를 남기고 기다려서, 방금 끝난 실행의 로그가 늦게 들어와 빠지지 않게 한다. 끝 표시 줄은 넣지 않는다. */
  static String logSince(int start) throws SQLException {
    String logs = awaitMark();
    int markLine = logs.lastIndexOf('\n', logs.lastIndexOf(MARK)) + 1;
    return logs.substring(start, Math.max(start, markLine));
  }

  static final String MARK = "hs-log-mark-";

  /** 표시 문장을 남기고 그것이 보이는 로그 전체를 돌려준다. 30초 안에 보이지 않으면 실패한다. */
  static String awaitMark() throws SQLException {
    String mark = MARK + UUID.randomUUID();
    admin(POSTGRES.getDatabaseName(), "SELECT '" + mark + "'");
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (true) {
      String logs = POSTGRES.getLogs();
      int at = logs.lastIndexOf(mark);
      if (at >= 0) {
        // 이 표시 뒤에 붙은 로그는 버린다: 다음 lastIndexOf(MARK)가 이 표시를 찾게
        int end = logs.indexOf('\n', at);
        return end < 0 ? logs : logs.substring(0, end + 1);
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("30초 안에 로그 표시가 보이지 않는다: " + mark);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("로그 표시를 기다리다 멈췄다", e);
      }
    }
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

  /** {@code db/migration}의 {@code V<버전>__*.sql}에서 버전만 숫자 순서로 모은다(Flyway installed_rank 순서와 같다). */
  static List<String> migrationVersions() {
    try {
      Path dir = Path.of(MigrationRunnerIntegrationTest.class.getResource("/db/migration").toURI());
      Pattern versioned = Pattern.compile("V([0-9]+)__.+\\.sql");
      try (var files = Files.list(dir)) {
        List<String> versions =
            files
                .map(file -> versioned.matcher(file.getFileName().toString()))
                .filter(java.util.regex.Matcher::matches)
                .map(matcher -> matcher.group(1))
                .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                .toList();
        if (versions.isEmpty()) {
          throw new IllegalStateException("db/migration에 V 파일이 없다");
        }
        return versions;
      }
    } catch (Exception exception) {
      throw new IllegalStateException("db/migration 목록을 읽지 못했다", exception);
    }
  }
}
