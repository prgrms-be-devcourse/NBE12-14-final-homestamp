package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chilsuitjo.back.global.config.migration.DatabaseGrants;
import com.chilsuitjo.back.global.config.migration.GrantRoles;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class DatabaseRoleBootstrapIntegrationTest {
  private static final String OWNER = "homestamp_owner";
  private static final String MIGRATION = "homestamp_migration";
  private static final String APP = "homestamp_app";
  private static final String PURGE = "homestamp_purge";

  /** NOLOGIN Human 읽기 그룹(decisions 2026-11-25). grants.sql은 이 그룹에만 권한을 준다. */
  private static final String HUMAN = "homestamp_human_read";

  /** 관리자 절차로 따로 만드는 사람별 LOGIN 역할(테스트 전용 이름, 실제 사람 이름이 아님). */
  private static final String PERSON = "homestamp_person_test";

  private static final String BOOTSTRAP = "homestamp_bootstrap_test";
  private static final String FAKE_PASSWORD = UUID.randomUUID().toString();
  private static final List<String> LOGIN_ROLES = List.of(MIGRATION, APP, PURGE);

  @Test
  void bootstrapIsLeastPrivilegeRepeatableAndSupportsFlywayAndGrants() throws Exception {
    var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
    postgres.withDatabaseName("homestamp_roles_test").start();
    try {
      createBootstrapExecutor(postgres);

      createBadOwner(postgres);
      var rejectedBadOwner = runBootstrap(postgres);
      assertNotEquals(0, rejectedBadOwner.getExitCode());
      assertTrue(roleHasAttribute(postgres, OWNER, "rolcreatedb"));
      assertFalse(roleExists(postgres, MIGRATION));
      executeAsAdmin(postgres, "DROP ROLE " + OWNER);

      var firstRun = runBootstrap(postgres);
      assertEquals(0, firstRun.getExitCode());
      assertTrue(roleExists(postgres, OWNER));
      for (String role : LOGIN_ROLES) {
        assertTrue(roleExists(postgres, role));
      }
      assertTrue(roleExists(postgres, HUMAN));
      assertFalse(roleExists(postgres, PERSON));
      assertRoleAttributes(postgres);
      assertOwnerMembership(postgres);
      assertOwnerCannotLogin(postgres);

      var rejectedDuplicate = runBootstrap(postgres, OWNER, MIGRATION, APP, PURGE, APP);
      assertNotEquals(0, rejectedDuplicate.getExitCode());
      var rejectedUnsafeName =
          runBootstrap(postgres, "homestamp-owner", MIGRATION, APP, PURGE, HUMAN);
      assertNotEquals(0, rejectedUnsafeName.getExitCode());
      assertTrue(roleExists(postgres, OWNER));

      setFakePasswords(postgres);
      createPerson(postgres);

      var repeatRun = runBootstrap(postgres);
      assertEquals(0, repeatRun.getExitCode(), repeatRun.getStderr());
      for (String role : LOGIN_ROLES) {
        assertTrue(roleHasPassword(postgres, role));
      }

      assertCanAssumeOwner(postgres);
      assertCannotAssumeOwner(postgres, APP);
      assertCannotAssumeOwner(postgres, PURGE);
      assertCannotAssumeOwner(postgres, PERSON);
      assertPersonCannotSetHumanGroup(postgres);
      assertDatabaseAndSchemaBoundary(postgres);

      migrateAndApplyGrants(postgres);
      assertOwnerOwnsFlywayHistory(postgres);
      assertOwnerOwnsManagedObjects(postgres);
      assertActualApplicationPrivileges(postgres);
      assertHumanMinimalRead(postgres);
      assertGrantsAreRepeatableAndAtomic(postgres);
      assertHumanAccessAudit(postgres);
      assertValidUntilDoesNotEndOpenSessions(postgres);

      assertBootstrapFailureRollsBack(postgres);
      assertBootstrapRejectsDriftWithoutRepair(postgres);
      assertBootstrapRejectsUnexpectedOwnerMembership(postgres);
      assertBootstrapRejectsDirectPublicAccessWithoutRevokingIt(postgres);
      assertBootstrapRejectsPublicSchemaOwnedByApplicationRole(postgres);

      assertEquals(0, runBootstrap(postgres).getExitCode());
    } finally {
      postgres.stop();
    }
  }

  private static void createBootstrapExecutor(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE "
              + BOOTSTRAP
              + " LOGIN CREATEROLE NOINHERIT PASSWORD '"
              + FAKE_PASSWORD
              + "' NOSUPERUSER NOCREATEDB NOREPLICATION NOBYPASSRLS");
      statement.execute("ALTER DATABASE " + postgres.getDatabaseName() + " OWNER TO " + BOOTSTRAP);
    }
  }

  private static void createBadOwner(PostgreSQLContainer postgres) throws Exception {
    executeAsAdmin(postgres, "CREATE ROLE " + OWNER + " NOLOGIN CREATEDB NOSUPERUSER NOCREATEROLE");
  }

  private static org.testcontainers.containers.Container.ExecResult runBootstrap(
      PostgreSQLContainer postgres) throws Exception {
    return runBootstrap(postgres, OWNER, MIGRATION, APP, PURGE, HUMAN);
  }

  private static org.testcontainers.containers.Container.ExecResult runBootstrap(
      PostgreSQLContainer postgres,
      String owner,
      String migration,
      String app,
      String purge,
      String human)
      throws Exception {
    Path script = Path.of("../infra/db/bootstrap_roles.sql").toAbsolutePath().normalize();
    postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/bootstrap_roles.sql");
    return postgres.execInContainer(
        "env",
        "PGPASSWORD=" + FAKE_PASSWORD,
        "psql",
        "-X",
        "-h",
        "localhost",
        "-U",
        BOOTSTRAP,
        "-d",
        postgres.getDatabaseName(),
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "owner_role=" + owner,
        "-v",
        "migration_role=" + migration,
        "-v",
        "app_role=" + app,
        "-v",
        "purge_role=" + purge,
        "-v",
        "human_role=" + human,
        "-f",
        "/tmp/bootstrap_roles.sql");
  }

  private static void assertRoleAttributes(PostgreSQLContainer postgres) throws Exception {
    assertRole(postgres, OWNER, false);
    assertRole(postgres, HUMAN, false);
    for (String role : LOGIN_ROLES) {
      assertRole(postgres, role, true);
    }
  }

  private static void assertRole(PostgreSQLContainer postgres, String role, boolean login)
      throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      assertEquals(login, roleHasAttribute(connection, role, "rolcanlogin"), role + " LOGIN");
      assertFalse(roleHasAttribute(connection, role, "rolinherit"), role + " INHERIT");
      assertFalse(roleHasAttribute(connection, role, "rolsuper"), role + " SUPERUSER");
      assertFalse(roleHasAttribute(connection, role, "rolcreatedb"), role + " CREATEDB");
      assertFalse(roleHasAttribute(connection, role, "rolcreaterole"), role + " CREATEROLE");
      assertFalse(roleHasAttribute(connection, role, "rolreplication"), role + " REPLICATION");
      assertFalse(roleHasAttribute(connection, role, "rolbypassrls"), role + " BYPASSRLS");
    }
  }

  private static void assertOwnerMembership(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      assertTrue(hasRolePrivilege(connection, MIGRATION, OWNER, "SET"));
      assertFalse(hasRolePrivilege(connection, MIGRATION, OWNER, "USAGE"));
      assertFalse(hasRolePrivilege(connection, MIGRATION, OWNER, "SET WITH ADMIN OPTION"));
      for (String role : List.of(APP, PURGE, HUMAN)) {
        assertFalse(hasRolePrivilege(connection, role, OWNER, "MEMBER"), role + " Owner member");
      }
      assertEquals(
          "false:false:true",
          scalar(
              connection,
              "SELECT m.inherit_option::text || ':' || m.admin_option::text || ':' || m.set_option::text "
                  + "FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid "
                  + "JOIN pg_roles u ON u.oid = m.member WHERE r.rolname = '"
                  + OWNER
                  + "' AND u.rolname = '"
                  + MIGRATION
                  + "'"));
    }
  }

  private static void assertCanAssumeOwner(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = roleConnection(postgres, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      assertEquals(OWNER, scalar(connection, "SELECT current_user"));
      statement.execute("RESET ROLE");
      assertFalse(hasDatabasePrivilege(connection, "CREATE"));
      assertThrows(SQLException.class, () -> statement.execute("GRANT " + OWNER + " TO " + APP));
    }
  }

  private static void assertOwnerCannotLogin(PostgreSQLContainer postgres) {
    assertThrows(SQLException.class, () -> roleConnection(postgres, OWNER));
  }

  private static void assertCannotAssumeOwner(PostgreSQLContainer postgres, String role)
      throws Exception {
    try (Connection connection = roleConnection(postgres, role);
        Statement statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + OWNER));
    }
  }

  private static void assertDatabaseAndSchemaBoundary(PostgreSQLContainer postgres)
      throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      assertTrue(hasDatabasePrivilege(connection, OWNER, "CREATE"));
      for (String role : List.of(MIGRATION, APP, PURGE, HUMAN, PERSON)) {
        assertFalse(hasDatabasePrivilege(connection, role, "CREATE"), role + " database CREATE");
        assertFalse(hasDatabasePrivilege(connection, role, "TEMP"), role + " database TEMP");
      }
      assertFalse(hasSchemaPrivilege(connection, "public", "USAGE"));
      assertFalse(hasSchemaPrivilege(connection, "public", "CREATE"));
      assertTrue(hasSchemaPrivilege(connection, OWNER, "USAGE"));
      assertTrue(hasSchemaPrivilege(connection, OWNER, "CREATE"));
      for (String role : List.of(MIGRATION, APP, PURGE, HUMAN, PERSON)) {
        assertFalse(hasSchemaPrivilege(connection, role, "USAGE"), role + " public USAGE");
        assertFalse(hasSchemaPrivilege(connection, role, "CREATE"), role + " public CREATE");
      }
    }
  }

  private static final GrantRoles GRANT_ROLES = new GrantRoles(OWNER, APP, PURGE, HUMAN);

  /**
   * #173 `--migrate`와 같은 Flyway 계약으로 Migration을 적용하고, 운영 GRANT 실행기({@link DatabaseGrants})로 GRANT를
   * 적용한다.
   */
  private static void migrateAndApplyGrants(PostgreSQLContainer postgres) throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), MIGRATION, FAKE_PASSWORD)
        .initSql("SET ROLE \"" + OWNER + "\"")
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .failOnMissingLocations(true)
        .skipDefaultCallbacks(true)
        .cleanDisabled(true)
        .load()
        .migrate();

    var observed = applyGrants(postgres);
    assertEquals(MIGRATION, observed.sessionUser());
    assertEquals(OWNER, observed.currentUser());
  }

  private static DatabaseGrants.Observed applyGrants(PostgreSQLContainer postgres)
      throws Exception {
    try (Connection connection = roleConnection(postgres, MIGRATION)) {
      return DatabaseGrants.apply(connection, GRANT_ROLES);
    }
  }

  /** 재실행은 같은 권한을 만들고, COMMIT 전에 실패하면 이미 실행한 GRANT·REVOKE까지 모두 되돌린다. */
  private static void assertGrantsAreRepeatableAndAtomic(PostgreSQLContainer postgres)
      throws Exception {
    String first = aclSnapshot(postgres);
    applyGrants(postgres);
    assertEquals(first, aclSnapshot(postgres), "GRANT 재실행 결과가 같다");

    executeAsAdmin(postgres, "GRANT SELECT ON state.member TO " + HUMAN);
    // COMMIT 시점 오류(테스트 전용, 운영 코드에 훅 없음): 권한 변경 중 event trigger가 표시 행을 넣고 지연 trigger가 COMMIT에서 실패
    executeAsAdmin(
        postgres,
        "CREATE SCHEMA hscommit; CREATE TABLE hscommit.mark (id int);"
            + " CREATE FUNCTION hscommit.fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'injected commit failure'; END $$;"
            + " CREATE CONSTRAINT TRIGGER hscommit_fail AFTER INSERT ON hscommit.mark"
            + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hscommit.fail_at_commit();"
            + " CREATE FUNCTION hscommit.mark_once() RETURNS event_trigger LANGUAGE plpgsql"
            + " SECURITY DEFINER SET search_path = pg_catalog AS $$ BEGIN INSERT INTO hscommit.mark"
            + " SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM hscommit.mark); END $$;"
            + " CREATE EVENT TRIGGER hscommit_mark ON ddl_command_end"
            + " WHEN TAG IN ('GRANT', 'REVOKE', 'ALTER DEFAULT PRIVILEGES')"
            + " EXECUTE FUNCTION hscommit.mark_once()");
    try (Connection connection = roleConnection(postgres, MIGRATION)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, GRANT_ROLES));
      assertTrue(
          String.valueOf(failure.getMessage()).contains("injected commit failure"),
          failure.getMessage());
      assertTrue(connection.getAutoCommit(), "자동 커밋 설정을 되돌린다");
    }
    executeAsAdmin(postgres, "DROP EVENT TRIGGER hscommit_mark; DROP SCHEMA hscommit CASCADE");
    try (Connection connection = adminConnection(postgres)) {
      assertEquals(
          "true",
          scalar(
              connection,
              "SELECT has_table_privilege('" + HUMAN + "', 'state.member', 'SELECT')::text"),
          "실패한 실행이 한 회수까지 되돌아가 과잉 권한이 그대로 남는다");
    }
    assertNotEquals(first, aclSnapshot(postgres));
    applyGrants(postgres);
    assertEquals(first, aclSnapshot(postgres), "성공한 재실행이 과잉 권한을 회수한다");
  }

  private static String aclSnapshot(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return scalar(
          connection,
          "SELECT string_agg(x, E'\\n' ORDER BY x) FROM ("
              + "SELECT 'rel ' || c.oid::regclass || ' ' || coalesce(c.relacl::text, '<default>') x"
              + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
              + " WHERE n.nspname IN ('record','state','log','public') AND c.relkind IN ('r','p','S','v','m')"
              + " UNION ALL SELECT 'col ' || a.attrelid::regclass || '.' || a.attname || ' ' || a.attacl::text"
              + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
              + " JOIN pg_namespace n ON n.oid = c.relnamespace"
              + " WHERE n.nspname IN ('record','state','log','public') AND a.attnum > 0"
              + " AND NOT a.attisdropped AND a.attacl IS NOT NULL"
              + " UNION ALL SELECT 'fn ' || p.oid::regprocedure || ' ' || coalesce(p.proacl::text, '<default>')"
              + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
              + " WHERE n.nspname IN ('record','state','log','public')"
              + " UNION ALL SELECT 'def ' || d.defaclrole::regrole || ' ' || d.defaclnamespace || ' '"
              + " || d.defaclobjtype::text || ' ' || d.defaclacl::text FROM pg_default_acl d) s");
    }
  }

  private static void assertOwnerOwnsFlywayHistory(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      assertEquals(
          OWNER,
          scalar(
              connection,
              "SELECT r.rolname FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner "
                  + "WHERE c.oid = 'public.flyway_schema_history'::regclass"));
    }
  }

  private static void assertOwnerOwnsManagedObjects(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres);
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          scalarInt(
              statement,
              "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                  + "WHERE (n.nspname IN ('record', 'state', 'log') OR "
                  + "(n.nspname = 'public' AND c.relname = 'flyway_schema_history')) "
                  + "AND c.relowner <> '"
                  + OWNER
                  + "'::regrole"));
      assertEquals(
          0,
          scalarInt(
              statement,
              "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                  + "WHERE n.nspname IN ('record', 'state', 'log') AND p.proowner <> '"
                  + OWNER
                  + "'::regrole"));
      assertEquals(
          0,
          scalarInt(
              statement,
              "SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                  + "WHERE n.nspname IN ('record', 'state', 'log') AND t.typowner <> '"
                  + OWNER
                  + "'::regrole"));
    }
  }

  private static void assertActualApplicationPrivileges(PostgreSQLContainer postgres)
      throws Exception {
    try (Connection connection = roleConnection(postgres, APP);
        Statement statement = connection.createStatement()) {
      assertEquals(0, scalarInt(statement, "SELECT count(*) FROM record.supplement"));
      statement.execute(
          "INSERT INTO record.supplement (id, room_record_id, repair_case_id, payment_id, "
              + "inspection_item_id, side, description, created_at) "
              + "SELECT NULL::uuid, NULL::uuid, NULL::uuid, NULL::uuid, NULL::uuid, 'TENANT', "
              + "NULL::text, NULL::timestamptz WHERE false");
      assertPermissionDenied(
          statement, "UPDATE record.supplement SET description = NULL WHERE false");
      assertPermissionDenied(statement, "DELETE FROM record.supplement WHERE false");
      assertPermissionDenied(statement, "TRUNCATE record.supplement");
      assertPermissionDenied(statement, "CREATE TABLE record.app_must_not_create (id integer)");
      assertPermissionDenied(statement, "CREATE TEMP TABLE app_must_not_create (id integer)");
      assertPermissionDenied(statement, "DELETE FROM record.action WHERE false");
    }
    try (Connection connection = roleConnection(postgres, PERSON);
        Statement statement = connection.createStatement()) {
      assertEquals(0, scalarInt(statement, "SELECT count(*) FROM record.supplement"));
      assertPermissionDenied(
          statement,
          "INSERT INTO record.supplement (id, room_record_id, repair_case_id, payment_id, "
              + "inspection_item_id, side, created_at) SELECT NULL::uuid, NULL::uuid, "
              + "NULL::uuid, NULL::uuid, NULL::uuid, 'TENANT', NULL::timestamptz WHERE false");
      assertPermissionDenied(
          statement, "UPDATE record.supplement SET description = NULL WHERE false");
      assertPermissionDenied(statement, "DELETE FROM record.action WHERE false");
    }
    assertTrue(hasFunctionPrivilege(postgres, PURGE, "state.purge_room_record(uuid)"));
    assertFalse(hasFunctionPrivilege(postgres, APP, "state.purge_room_record(uuid)"));
    assertFalse(hasFunctionPrivilege(postgres, HUMAN, "state.purge_room_record(uuid)"));
    assertFalse(hasFunctionPrivilege(postgres, PERSON, "state.purge_room_record(uuid)"));
    try (Connection connection = roleConnection(postgres, PURGE);
        Statement statement = connection.createStatement()) {
      assertPermissionDenied(statement, "DELETE FROM record.action WHERE false");
    }
  }

  /** #156 Human 최소 조회: 사람 역할은 그룹 권한만 받아 허용 컬럼만 읽고, 민감 컬럼은 42501. */
  private static void assertHumanMinimalRead(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = roleConnection(postgres, PERSON);
        Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          scalarInt(
              statement,
              "SELECT count(id) FROM record.cause_note WHERE basis IS NOT NULL AND text IS NOT NULL"));
      assertEquals(
          0, scalarInt(statement, "SELECT count(id) FROM state.member WHERE status IS NOT NULL"));
      for (String sql :
          List.of(
              "SELECT expert_name FROM record.cause_note",
              "SELECT id FROM record.cause_note WHERE expert_name IS NOT NULL",
              "SELECT * FROM record.cause_note",
              "SELECT c FROM record.cause_note c",
              "SELECT password_hash FROM state.member",
              "SELECT phone FROM state.member",
              "SELECT token_hash FROM state.refresh_token",
              "SELECT code_hash FROM state.phone_verification",
              "SELECT token_hash FROM state.link",
              "SELECT invite_phone FROM state.party",
              "SELECT payload FROM state.outbox",
              "UPDATE record.cause_note SET id = id WHERE false",
              "DELETE FROM state.member WHERE false")) {
        assertPermissionDenied(statement, sql);
      }
    }
  }

  /** 사람 역할: LOGIN·NOINHERIT·만료일, 그룹 멤버십은 INHERIT TRUE·SET FALSE·ADMIN FALSE. */
  private static void createPerson(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE "
              + PERSON
              + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"
              + " PASSWORD '"
              + FAKE_PASSWORD
              + "' VALID UNTIL '2999-01-01'");
      statement.execute(
          "GRANT " + HUMAN + " TO " + PERSON + " WITH INHERIT TRUE, SET FALSE, ADMIN FALSE");
    }
  }

  private static void assertPersonCannotSetHumanGroup(PostgreSQLContainer postgres)
      throws Exception {
    try (Connection connection = roleConnection(postgres, PERSON);
        Statement statement = connection.createStatement()) {
      assertPermissionDenied(statement, "SET ROLE " + HUMAN);
      assertEquals(PERSON, scalar(connection, "SELECT current_user"));
    }
  }

  /** 점검 스크립트: 정상이면 통과, 직접 권한·위험 멤버십·SET 가능 멤버십은 실패. 아무것도 고치지 않는다. */
  private static void assertHumanAccessAudit(PostgreSQLContainer postgres) throws Exception {
    var passed = runHumanAudit(postgres);
    assertEquals(0, passed.getExitCode(), passed.getStderr());
    assertTrue(passed.getStderr().contains("1 person role(s)"), passed.getStderr());

    executeAsAdmin(postgres, "GRANT SELECT (status) ON state.member TO " + PERSON);
    assertAuditFails(postgres, "has direct privileges");
    assertTrue(hasColumnPrivilege(postgres, PERSON, "state.member", "status"));
    executeAsAdmin(postgres, "REVOKE SELECT (status) ON state.member FROM " + PERSON);

    // 6개 카탈로그 밖의 직접 권한도 pg_shdepend로 잡는다
    executeAsAdmin(postgres, "GRANT USAGE ON LANGUAGE plpgsql TO " + PERSON);
    assertAuditFails(postgres, "(pg_shdepend)");
    executeAsAdmin(postgres, "REVOKE USAGE ON LANGUAGE plpgsql FROM " + PERSON);

    executeAsAdmin(postgres, "GRANT pg_read_all_data TO " + PERSON);
    assertAuditFails(postgres, "membership other than the human read group");
    executeAsAdmin(postgres, "REVOKE pg_read_all_data FROM " + PERSON);

    executeAsAdmin(postgres, "REVOKE " + HUMAN + " FROM " + PERSON);
    executeAsAdmin(
        postgres, "GRANT " + HUMAN + " TO " + PERSON + " WITH INHERIT TRUE, SET TRUE, ADMIN FALSE");
    assertAuditFails(postgres, "must be INHERIT TRUE, SET FALSE, ADMIN FALSE");
    assertNotEquals(0, runBootstrap(postgres).getExitCode());
    executeAsAdmin(postgres, "REVOKE " + HUMAN + " FROM " + PERSON);
    executeAsAdmin(
        postgres, "GRANT " + HUMAN + " TO " + PERSON + " WITH INHERIT TRUE, SET FALSE, ADMIN TRUE");
    assertAuditFails(postgres, "must be INHERIT TRUE, SET FALSE, ADMIN FALSE");
    executeAsAdmin(postgres, "REVOKE " + HUMAN + " FROM " + PERSON);
    executeAsAdmin(
        postgres,
        "GRANT " + HUMAN + " TO " + PERSON + " WITH INHERIT TRUE, SET FALSE, ADMIN FALSE");

    // 관리자 예외는 CREATEROLE·superuser의 ADMIN 전용 멤버십만이다. 일반 LOGIN 역할의 ADMIN 전용은 실패
    String adminOnly = "homestamp_admin_only_test";
    executeAsAdmin(
        postgres, "CREATE ROLE " + adminOnly + " LOGIN NOINHERIT VALID UNTIL '2999-01-01'");
    executeAsAdmin(
        postgres,
        "GRANT " + HUMAN + " TO " + adminOnly + " WITH INHERIT FALSE, SET FALSE, ADMIN TRUE");
    assertAuditFails(postgres, "person role " + adminOnly + " membership must be");
    executeAsAdmin(postgres, "REVOKE " + HUMAN + " FROM " + adminOnly);

    // 사람 역할 자체의 ADMIN 전용 부여도 CREATEROLE·superuser만 받는다(#185 리뷰). 일반 LOGIN 역할이면 실패
    executeAsAdmin(
        postgres,
        "GRANT " + PERSON + " TO " + adminOnly + " WITH INHERIT FALSE, SET FALSE, ADMIN TRUE");
    assertAuditFails(postgres, "another role can use or administer person role " + PERSON);
    executeAsAdmin(postgres, "REVOKE " + PERSON + " FROM " + adminOnly);
    executeAsAdmin(postgres, "DROP ROLE " + adminOnly);

    String roleAdmin = "homestamp_role_admin_test";
    executeAsAdmin(postgres, "CREATE ROLE " + roleAdmin + " LOGIN NOINHERIT CREATEROLE");
    executeAsAdmin(
        postgres,
        "GRANT " + PERSON + " TO " + roleAdmin + " WITH INHERIT FALSE, SET FALSE, ADMIN TRUE");
    var trustedAdmin = runHumanAudit(postgres);
    assertEquals(0, trustedAdmin.getExitCode(), trustedAdmin.getStderr());
    executeAsAdmin(postgres, "REVOKE " + PERSON + " FROM " + roleAdmin);
    executeAsAdmin(postgres, "DROP ROLE " + roleAdmin);

    var restored = runHumanAudit(postgres);
    assertEquals(0, restored.getExitCode(), restored.getStderr());
    assertTrue(
        restored.getStderr().contains("ADMIN-only grants: {" + BOOTSTRAP + "}"),
        restored.getStderr());
  }

  private static void assertAuditFails(PostgreSQLContainer postgres, String message)
      throws Exception {
    var result = runHumanAudit(postgres);
    assertNotEquals(0, result.getExitCode());
    assertTrue(result.getStderr().contains(message), result.getStderr());
  }

  /** VALID UNTIL은 그 뒤의 비밀번호 로그인만 막는다. 이미 열린 세션은 남으므로 회수 때 pg_terminate_backend를 따로 한다. */
  private static void assertValidUntilDoesNotEndOpenSessions(PostgreSQLContainer postgres)
      throws Exception {
    try (Connection open = roleConnection(postgres, PERSON);
        Statement statement = open.createStatement()) {
      String pid = scalar(open, "SELECT pg_backend_pid()::text");
      executeAsAdmin(postgres, "ALTER ROLE " + PERSON + " VALID UNTIL '2000-01-01'");
      assertThrows(SQLException.class, () -> roleConnection(postgres, PERSON).close());
      assertEquals(0, scalarInt(statement, "SELECT count(id) FROM state.member"));

      executeAsAdmin(postgres, "SELECT pg_terminate_backend(" + pid + ")");
      assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
    } finally {
      executeAsAdmin(postgres, "ALTER ROLE " + PERSON + " VALID UNTIL '2999-01-01'");
    }
    roleConnection(postgres, PERSON).close();
  }

  /** public 소유자 검사는 ACL 검사가 소유자를 건너뛰므로 따로 한다. 소유권은 바꾸지 않고 실패한다. */
  private static void assertBootstrapRejectsPublicSchemaOwnedByApplicationRole(
      PostgreSQLContainer postgres) throws Exception {
    String originalOwner = schemaOwner(postgres, "public");
    executeAsAdmin(postgres, "ALTER SCHEMA public OWNER TO " + APP);
    var result = runBootstrap(postgres);
    assertNotEquals(0, result.getExitCode());
    assertTrue(result.getStderr().contains("public schema is owned by"), result.getStderr());
    assertEquals(APP, schemaOwner(postgres, "public"));

    executeAsAdmin(postgres, "ALTER SCHEMA public OWNER TO " + PERSON);
    assertNotEquals(0, runBootstrap(postgres).getExitCode());
    assertEquals(PERSON, schemaOwner(postgres, "public"));

    // 실행 관리자는 Human 그룹을 만들 때 ADMIN 전용 멤버십을 받는다. 사람 계정이 아니므로 public을 소유해도 통과한다(#185 리뷰)
    executeAsAdmin(postgres, "ALTER SCHEMA public OWNER TO " + BOOTSTRAP);
    var adminOwned = runBootstrap(postgres);
    assertEquals(0, adminOwned.getExitCode(), adminOwned.getStderr());
    assertEquals(BOOTSTRAP, schemaOwner(postgres, "public"));
    executeAsAdmin(postgres, "ALTER SCHEMA public OWNER TO " + originalOwner);
  }

  private static org.testcontainers.containers.Container.ExecResult runHumanAudit(
      PostgreSQLContainer postgres) throws Exception {
    Path script = Path.of("../infra/db/check_human_access.sql").toAbsolutePath().normalize();
    postgres.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/check_human_access.sql");
    return postgres.execInContainer(
        "env",
        "PGPASSWORD=" + FAKE_PASSWORD,
        "psql",
        "-X",
        "-h",
        "localhost",
        "-U",
        BOOTSTRAP,
        "-d",
        postgres.getDatabaseName(),
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "human_role=" + HUMAN,
        "-f",
        "/tmp/check_human_access.sql");
  }

  private static boolean hasColumnPrivilege(
      PostgreSQLContainer postgres, String role, String table, String column) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT has_column_privilege('"
                  + role
                  + "', '"
                  + table
                  + "', '"
                  + column
                  + "', 'SELECT')::text"));
    }
  }

  private static void assertPermissionDenied(Statement statement, String sql) {
    SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
    assertEquals("42501", failure.getSQLState(), "권한 거부여야 한다: " + sql);
  }

  private static void assertBootstrapRejectsDriftWithoutRepair(PostgreSQLContainer postgres)
      throws Exception {
    executeAsAdmin(postgres, "ALTER ROLE " + APP + " CREATEROLE");
    var result = runBootstrap(postgres);
    assertNotEquals(0, result.getExitCode());
    assertTrue(roleHasAttribute(postgres, APP, "rolcreaterole"));
    executeAsAdmin(postgres, "ALTER ROLE " + APP + " NOCREATEROLE");
  }

  private static void assertBootstrapFailureRollsBack(PostgreSQLContainer postgres)
      throws Exception {
    executeAsAdmin(
        postgres, "REVOKE CREATE ON DATABASE " + postgres.getDatabaseName() + " FROM " + OWNER);
    executeAsAdmin(postgres, "ALTER SCHEMA record OWNER TO " + APP);
    executeAsAdmin(postgres, "GRANT USAGE ON SCHEMA public TO PUBLIC");

    var result = runBootstrap(postgres);
    assertNotEquals(0, result.getExitCode());
    assertFalse(hasDatabasePrivilege(postgres, OWNER, "CREATE"));
    assertTrue(hasSchemaPrivilege(postgres, "public", "USAGE"));
    assertEquals(APP, schemaOwner(postgres, "record"));

    executeAsAdmin(postgres, "REVOKE USAGE ON SCHEMA public FROM PUBLIC");
    executeAsAdmin(postgres, "ALTER SCHEMA record OWNER TO " + OWNER);
    executeAsAdmin(
        postgres, "GRANT CREATE ON DATABASE " + postgres.getDatabaseName() + " TO " + OWNER);
  }

  private static void assertBootstrapRejectsUnexpectedOwnerMembership(PostgreSQLContainer postgres)
      throws Exception {
    executeAsAdmin(postgres, "GRANT " + OWNER + " TO " + APP + " WITH SET TRUE");
    var result = runBootstrap(postgres);
    assertNotEquals(0, result.getExitCode());
    assertTrue(hasRolePrivilege(postgres, APP, OWNER, "SET"));
    executeAsAdmin(postgres, "REVOKE " + OWNER + " FROM " + APP);
  }

  private static void assertBootstrapRejectsDirectPublicAccessWithoutRevokingIt(
      PostgreSQLContainer postgres) throws Exception {
    executeAsAdmin(postgres, "GRANT CREATE ON SCHEMA public TO " + APP);
    var result = runBootstrap(postgres);
    assertNotEquals(0, result.getExitCode());
    assertTrue(hasSchemaPrivilege(postgres, APP, "CREATE"));
    executeAsAdmin(postgres, "REVOKE CREATE ON SCHEMA public FROM " + APP);
  }

  private static void setFakePasswords(PostgreSQLContainer postgres) throws Exception {
    try (Connection connection = adminConnection(postgres);
        Statement statement = connection.createStatement()) {
      for (String role : LOGIN_ROLES) {
        statement.execute("ALTER ROLE " + role + " PASSWORD '" + FAKE_PASSWORD + "'");
      }
    }
  }

  private static boolean roleExists(PostgreSQLContainer postgres, String role) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + role + "')::text"));
    }
  }

  private static boolean roleHasPassword(PostgreSQLContainer postgres, String role)
      throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT (rolpassword IS NOT NULL)::text FROM pg_authid WHERE rolname = '"
                  + role
                  + "'"));
    }
  }

  private static boolean roleHasAttribute(
      PostgreSQLContainer postgres, String role, String attribute) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return roleHasAttribute(connection, role, attribute);
    }
  }

  private static boolean roleHasAttribute(Connection connection, String role, String attribute)
      throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT " + attribute + "::text FROM pg_roles WHERE rolname = '" + role + "'"));
  }

  private static boolean hasRolePrivilege(
      PostgreSQLContainer postgres, String member, String role, String privilege) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return hasRolePrivilege(connection, member, role, privilege);
    }
  }

  private static boolean hasRolePrivilege(
      Connection connection, String member, String role, String privilege) throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT pg_has_role('" + member + "', '" + role + "', '" + privilege + "')::text"));
  }

  private static boolean hasDatabasePrivilege(
      PostgreSQLContainer postgres, String role, String privilege) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return hasDatabasePrivilege(connection, role, privilege);
    }
  }

  private static boolean hasDatabasePrivilege(Connection connection, String privilege)
      throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_database_privilege(current_user, current_database(), '"
                + privilege
                + "')::text"));
  }

  private static boolean hasDatabasePrivilege(Connection connection, String role, String privilege)
      throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_database_privilege('"
                + role
                + "', current_database(), '"
                + privilege
                + "')::text"));
  }

  private static boolean hasSchemaPrivilege(
      PostgreSQLContainer postgres, String role, String privilege) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return hasSchemaPrivilege(connection, role, privilege);
    }
  }

  private static String schemaOwner(PostgreSQLContainer postgres, String schema) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return scalar(
          connection,
          "SELECT r.rolname FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner "
              + "WHERE n.nspname = '"
              + schema
              + "'");
    }
  }

  private static boolean hasSchemaPrivilege(Connection connection, String role, String privilege)
      throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_schema_privilege('" + role + "', 'public', '" + privilege + "')::text"));
  }

  private static boolean hasFunctionPrivilege(
      PostgreSQLContainer postgres, String role, String function) throws Exception {
    try (Connection connection = adminConnection(postgres)) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT has_function_privilege('"
                  + role
                  + "', '"
                  + function
                  + "', 'EXECUTE')::text"));
    }
  }

  private static void executeAsAdmin(PostgreSQLContainer postgres, String sql) throws SQLException {
    try (Connection connection = adminConnection(postgres);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Connection adminConnection(PostgreSQLContainer postgres) throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection roleConnection(PostgreSQLContainer postgres, String role)
      throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), role, FAKE_PASSWORD);
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }

  private static int scalarInt(Statement statement, String sql) throws SQLException {
    try (var result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }
}
