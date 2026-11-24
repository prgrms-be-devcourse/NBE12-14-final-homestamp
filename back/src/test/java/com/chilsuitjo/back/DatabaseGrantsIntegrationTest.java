package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class DatabaseGrantsIntegrationTest {
  private static final String OWNER = "homestamp_owner";
  private static final String MIGRATION = "homestamp_migration";
  private static final String APP = "homestamp_app";
  private static final String PURGE = "homestamp_purge";
  private static final String HUMAN = "homestamp_human";
  private static final String PASSWORD = UUID.randomUUID().toString();

  private static final Set<String> RECORD_TABLES =
      Set.of(
          "record.action",
          "record.cause_note",
          "record.inspection_item_version",
          "record.member_acknowledgement",
          "record.payment_version",
          "record.photo",
          "record.proposal",
          "record.proposal_line",
          "record.progress_note",
          "record.repair_content",
          "record.settlement_transfer_version",
          "record.supplement");

  private static final Set<String> STATE_TABLES =
      Set.of(
          "state.correction_request",
          "state.in_app_notification",
          "state.inspection",
          "state.inspection_item",
          "state.link",
          "state.member",
          "state.notice",
          "state.outbox",
          "state.party",
          "state.part_template",
          "state.payment",
          "state.phone_verification",
          "state.photo_visibility",
          "state.progress_note_response",
          "state.rate_counter",
          "state.region",
          "state.repair_case",
          "state.refresh_token",
          "state.response_request",
          "state.room_part",
          "state.room_record",
          "state.settlement",
          "state.settlement_item",
          "state.settlement_transfer",
          "state.sms_opt_out");

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

  @BeforeAll
  static void migrateAsOwnerThroughMigrationRole() throws Exception {
    POSTGRES.start();
    createRolesAndDatabasePrivileges();

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION, PASSWORD)
        .initSql("SET ROLE " + OWNER)
        .locations("classpath:db/migration")
        .schemas("public", "record", "state", "log")
        .defaultSchema("public")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void grantsAreLeastPrivilegeRepeatableAndApplyToFutureOwnerObjects() throws Exception {
    assertAllProjectTablesAreClassified();
    assertMigrationCanAssumeOwnerAndAppCannot();
    seedOverbroadDefaultPrivileges();
    assertUnclassifiedTableFailsClosed();
    assertGrantsRejectPublicSchemaAccess(
        "GRANT CREATE ON SCHEMA public TO " + APP, "REVOKE CREATE ON SCHEMA public FROM " + APP);
    assertGrantsRejectPublicSchemaAccess(
        "GRANT USAGE ON SCHEMA public TO PUBLIC", "REVOKE USAGE ON SCHEMA public FROM PUBLIC");

    applyGrants();
    applyGrants();

    assertTablePrivilegeMatrix();
    assertRolePrivileges(APP, "public.flyway_schema_history", false, false, false, false);
    assertRolePrivileges(PURGE, "public.flyway_schema_history", false, false, false, false);
    assertRolePrivileges(HUMAN, "public.flyway_schema_history", false, false, false, false);
    assertFalse(hasDatabaseCreatePrivilege(APP));
    assertFalse(hasDatabaseCreatePrivilege(PURGE));
    assertFalse(hasDatabaseCreatePrivilege(HUMAN));
    assertPublicSchemaAccessDenied(APP);
    assertPublicSchemaAccessDenied(PURGE);
    assertPublicSchemaAccessDenied(HUMAN);
    assertFalse(hasPublicSchemaPrivilege("USAGE"));
    assertFalse(hasPublicSchemaPrivilege("CREATE"));
    assertAppPermissions();
    assertPurgePermissions();
    assertHumanIsReadOnly();
    assertExistingFunctionPermissions();
    assertDefaultPrivilegesForNewObjects();
  }

  private static void createRolesAndDatabasePrivileges() throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE " + OWNER + " NOLOGIN");
      statement.execute("CREATE ROLE " + MIGRATION + " LOGIN PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE " + APP + " LOGIN PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE " + PURGE + " LOGIN PASSWORD '" + PASSWORD + "'");
      statement.execute("CREATE ROLE " + HUMAN + " LOGIN PASSWORD '" + PASSWORD + "'");
      statement.execute("GRANT " + OWNER + " TO " + MIGRATION);
      statement.execute("GRANT CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + OWNER);
      statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
      statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + OWNER);
    }
  }

  private static void seedOverbroadDefaultPrivileges() throws SQLException {
    try (Connection connection = roleConnection(MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + OWNER
              + " GRANT UPDATE, DELETE ON TABLES TO "
              + APP);
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + OWNER
              + " IN SCHEMA state GRANT UPDATE, DELETE ON TABLES TO "
              + APP);
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT EXECUTE ON FUNCTIONS TO PUBLIC");
    }
  }

  private static void assertGrantsRejectPublicSchemaAccess(String grantSql, String revokeSql)
      throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(grantSql);
    }
    try (Connection connection = adminConnection()) {
      assertTrue(
          hasSchemaPrivilege(
              connection, APP, "public", grantSql.contains(" TO PUBLIC") ? "USAGE" : "CREATE"));
    }

    var result = executeGrants();
    assertTrue(result.getExitCode() != 0, result.getStdout() + result.getStderr());
    try (Connection connection = adminConnection()) {
      assertFalse(hasTablePrivilege(connection, APP, "record.action", "SELECT"));
    }

    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(revokeSql);
    }
  }

  private static void assertUnclassifiedTableFailsClosed() throws Exception {
    try (Connection connection = roleConnection(MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      statement.execute("CREATE TABLE record.unclassified_grant_table (id integer)");
      statement.execute(
          "REVOKE ALL ON TABLE record.unclassified_grant_table FROM "
              + APP
              + ", "
              + PURGE
              + ", "
              + HUMAN);
    }

    var result = executeGrants();
    assertTrue(result.getExitCode() != 0, result.getStdout() + result.getStderr());
    try (Connection connection = adminConnection()) {
      assertFalse(hasTablePrivilege(connection, APP, "record.action", "SELECT"));
      assertFalse(hasTablePrivilege(connection, APP, "record.unclassified_grant_table", "SELECT"));
    }

    try (Connection connection = roleConnection(MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      statement.execute("DROP TABLE record.unclassified_grant_table");
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void assertAllProjectTablesAreClassified() throws SQLException {
    Set<String> actual =
        queryAsAdmin(
            "SELECT n.nspname || '.' || c.relname "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relkind IN ('r', 'p') AND n.nspname IN ('record', 'state', 'log')");
    assertEquals(RECORD_TABLES.size() + STATE_TABLES.size() + 1, actual.size());
    assertTrue(actual.containsAll(RECORD_TABLES));
    assertTrue(actual.containsAll(STATE_TABLES));
    assertTrue(actual.contains("log.deletion_log"));
    assertTrue(
        queryAsAdmin(
                "SELECT n.nspname || '.' || c.relname "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE c.relkind = 'S' AND n.nspname IN ('record', 'state', 'log')")
            .isEmpty());
    assertEquals(
        Set.of("public.flyway_schema_history"),
        queryAsAdmin(
            "SELECT n.nspname || '.' || c.relname "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relkind IN ('r', 'p') AND n.nspname = 'public'"));
  }

  private static void assertMigrationCanAssumeOwnerAndAppCannot() throws SQLException {
    try (Connection connection = roleConnection(MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      assertEquals(OWNER, scalar(connection, "SELECT current_user"));
    }
    try (Connection connection = roleConnection(APP);
        Statement statement = connection.createStatement()) {
      assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + OWNER));
    }
  }

  private static void applyGrants() throws Exception {
    var result = executeGrants();
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  private static org.testcontainers.containers.Container.ExecResult executeGrants()
      throws Exception {
    Path script = Path.of("../infra/db/grants.sql").toAbsolutePath().normalize();
    POSTGRES.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/grants.sql");
    return POSTGRES.execInContainer(
        "env",
        "PGPASSWORD=" + PASSWORD,
        "psql",
        "-X",
        "-h",
        "localhost",
        "-U",
        MIGRATION,
        "-d",
        POSTGRES.getDatabaseName(),
        "-v",
        "owner_role=" + OWNER,
        "-v",
        "app_role=" + APP,
        "-v",
        "purge_role=" + PURGE,
        "-v",
        "human_role=" + HUMAN,
        "-f",
        "/tmp/grants.sql");
  }

  private static void assertAppPermissions() throws SQLException {
    UUID withdrawnMember = UUID.randomUUID();
    UUID acknowledgement = UUID.randomUUID();
    UUID activeMember = UUID.randomUUID();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO state.member (id, status, withdrawn_at, created_at, version) VALUES ('"
              + withdrawnMember
              + "', 'WITHDRAWN', now(), now(), 0)");
      statement.execute(
          "INSERT INTO record.member_acknowledgement (id, member_id, kind, text_version, agreed_at, created_at) VALUES ('"
              + acknowledgement
              + "', '"
              + withdrawnMember
              + "', 'TERMS_AGREED', 'test', now(), now())");
      statement.execute(
          "INSERT INTO state.member (id, status, created_at, version) VALUES ('"
              + activeMember
              + "', 'ACTIVE', now(), 0)");
    }
    try (Connection connection = roleConnection(APP);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + APP);
      statement.execute("SELECT count(*) FROM record.action");
      statement.execute("SELECT count(*) FROM record.supplement");
      statement.execute(
          "INSERT INTO record.supplement (id, room_record_id, repair_case_id, side, created_at) "
              + "SELECT '00000000-0000-0000-0000-000000000001'::uuid, "
              + "'00000000-0000-0000-0000-000000000002'::uuid, "
              + "'00000000-0000-0000-0000-000000000003'::uuid, 'TENANT', now() WHERE false");
      assertEquals(
          "1",
          scalar(
              connection,
              "SELECT record.purge_member_acknowledgements('"
                  + withdrawnMember
                  + "', now())::text"));
      statement.execute(
          "INSERT INTO record.member_acknowledgement (id, member_id, kind, text_version, agreed_at, created_at) VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + activeMember
              + "', 'TERMS_AGREED', 'test', now(), now())");
      statement.execute(
          "INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES ('test-hmac', now())");
      statement.execute("DELETE FROM state.sms_opt_out WHERE phone_hmac = 'test-hmac'");
      assertPermissionDenied(statement, "UPDATE record.action SET created_at = created_at");
      assertPermissionDenied(statement, "UPDATE record.supplement SET description = 'changed'");
      assertPermissionDenied(statement, "DELETE FROM record.supplement");
      assertPermissionDenied(statement, "DELETE FROM record.member_acknowledgement");
      assertPermissionDenied(statement, "UPDATE state.sms_opt_out SET created_at = now()");
      assertPermissionDenied(statement, "DELETE FROM state.room_record");
      assertPermissionDenied(statement, "TRUNCATE record.action");
      assertPermissionDenied(statement, "CREATE TABLE state.app_must_not_create (id integer)");
      assertPermissionDenied(statement, "CREATE TABLE public.app_must_not_create (id integer)");
      assertPermissionDenied(
          statement, "SELECT state.purge_room_record('00000000-0000-0000-0000-000000000001')");
    }
  }

  private static void assertTablePrivilegeMatrix() throws SQLException {
    for (String table : RECORD_TABLES) {
      assertRolePrivileges(APP, table, true, true, false, false);
      assertRolePrivileges(PURGE, table, true, false, false, false);
      assertRolePrivileges(HUMAN, table, true, false, false, false);
    }
    for (String table : STATE_TABLES) {
      boolean lookup = table.equals("state.part_template") || table.equals("state.region");
      boolean optOut = table.equals("state.sms_opt_out");
      boolean room = table.equals("state.room_record");
      assertRolePrivileges(APP, table, true, !lookup, !lookup && !optOut, !lookup && !room);
      assertRolePrivileges(PURGE, table, true, table.equals("state.outbox"), false, false);
      assertRolePrivileges(HUMAN, table, true, false, false, false);
    }
    assertRolePrivileges(APP, "log.deletion_log", false, true, false, false);
    assertRolePrivileges(PURGE, "log.deletion_log", true, true, false, false);
    assertRolePrivileges(HUMAN, "log.deletion_log", true, false, false, false);
  }

  private static void assertRolePrivileges(
      String role, String table, boolean select, boolean insert, boolean update, boolean delete)
      throws SQLException {
    try (Connection connection = adminConnection()) {
      assertEquals(
          select, hasTablePrivilege(connection, role, table, "SELECT"), role + " SELECT " + table);
      assertEquals(
          insert, hasTablePrivilege(connection, role, table, "INSERT"), role + " INSERT " + table);
      assertEquals(
          update, hasTablePrivilege(connection, role, table, "UPDATE"), role + " UPDATE " + table);
      assertEquals(
          delete, hasTablePrivilege(connection, role, table, "DELETE"), role + " DELETE " + table);
      assertFalse(
          hasTablePrivilege(connection, role, table, "TRUNCATE"), role + " TRUNCATE " + table);
    }
  }

  private static void assertPurgePermissions() throws SQLException {
    try (Connection connection = roleConnection(PURGE);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + PURGE);
      statement.execute("SELECT count(*) FROM record.action");
      statement.execute(
          "INSERT INTO log.deletion_log (id, target_kind, target_id, reason, created_at) "
              + "VALUES (uuidv7(), 'TEST', uuidv7(), 'TEST', now())");
      statement.execute(
          "INSERT INTO state.outbox (id, kind, payload, status, attempts, next_attempt_at, created_at, version) "
              + "VALUES (uuidv7(), 'AUDIT_WRITE', '{}'::jsonb, 'PENDING', 0, now(), now(), 0)");
      assertPermissionDenied(statement, "DELETE FROM record.action");
      assertPermissionDenied(statement, "DELETE FROM state.room_record");
      assertPermissionDenied(statement, "TRUNCATE state.outbox");
      assertPermissionDenied(
          statement, "SELECT record.purge_member_acknowledgements(uuidv7(), now())");
      assertEquals(
          "false",
          scalar(
              connection,
              "SELECT state.purge_room_record('00000000-0000-0000-0000-000000000001')::text"));
    }
  }

  private static void assertHumanIsReadOnly() throws SQLException {
    try (Connection connection = roleConnection(HUMAN);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + HUMAN);
      statement.execute("SELECT count(*) FROM state.member");
      assertPermissionDenied(statement, "INSERT INTO state.sms_opt_out VALUES ('human', now())");
      assertPermissionDenied(statement, "UPDATE state.member SET status = status");
      assertPermissionDenied(statement, "DELETE FROM state.member");
      assertPermissionDenied(statement, "SELECT state.purge_room_record(uuidv7())");
    }
  }

  private static void assertExistingFunctionPermissions() throws SQLException {
    assertFunctionPermissions("record.forbid_change()", false, false, false);
    assertFunctionPermissions(
        "record.purge_member_acknowledgements(uuid, timestamp with time zone)", true, false, false);
    assertFunctionPermissions("state.forbid_link_revalidate()", false, false, false);
    assertFunctionPermissions("state.guard_room_delete()", false, false, false);
    assertFunctionPermissions("state.purge_room_record(uuid)", false, true, false);
  }

  private static void assertFunctionPermissions(
      String function, boolean appExecute, boolean purgeExecute, boolean humanExecute)
      throws SQLException {
    assertFalse(hasPublicFunctionExecute(function), "PUBLIC EXECUTE " + function);
    try (Connection connection = adminConnection()) {
      assertEquals(
          appExecute,
          hasFunctionPrivilege(connection, APP, function, "EXECUTE"),
          APP + " EXECUTE " + function);
      assertEquals(
          purgeExecute,
          hasFunctionPrivilege(connection, PURGE, function, "EXECUTE"),
          PURGE + " EXECUTE " + function);
      assertEquals(
          humanExecute,
          hasFunctionPrivilege(connection, HUMAN, function, "EXECUTE"),
          HUMAN + " EXECUTE " + function);
    }
  }

  private static void assertDefaultPrivilegesForNewObjects() throws SQLException {
    try (Connection connection = roleConnection(MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + OWNER);
      statement.execute("CREATE SEQUENCE record.grants_default_sequence");
      statement.execute(
          "CREATE TABLE record.grants_default_table (id bigint DEFAULT nextval('record.grants_default_sequence'), value text)");
      statement.execute("CREATE SEQUENCE state.grants_default_sequence");
      statement.execute(
          "CREATE TABLE state.grants_default_table (id bigint DEFAULT nextval('state.grants_default_sequence'), value text)");
      statement.execute("CREATE SEQUENCE log.grants_default_sequence");
      statement.execute(
          "CREATE TABLE log.grants_default_table (id bigint DEFAULT nextval('log.grants_default_sequence'), value text)");
      statement.execute(
          "CREATE FUNCTION state.grants_default_function() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
    }

    try (Connection connection = roleConnection(APP)) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO record.grants_default_table (value) VALUES ('app')");
        statement.execute("INSERT INTO state.grants_default_table (value) VALUES ('app')");
        statement.execute("INSERT INTO log.grants_default_table (value) VALUES ('app')");
        statement.execute("SELECT count(*) FROM state.grants_default_table");
        assertPermissionDenied(
            statement, "UPDATE state.grants_default_table SET value = 'updated'");
        assertPermissionDenied(statement, "DELETE FROM state.grants_default_table");
      }
      assertRolePrivileges(APP, "record.grants_default_table", true, true, false, false);
      assertRolePrivileges(APP, "state.grants_default_table", true, true, false, false);
      assertRolePrivileges(APP, "log.grants_default_table", false, true, false, false);
      for (String schema : Set.of("record", "state", "log")) {
        assertTrue(
            hasSequencePrivilege(connection, APP, schema + ".grants_default_sequence", "USAGE"));
        assertTrue(
            hasSequencePrivilege(connection, APP, schema + ".grants_default_sequence", "SELECT"));
        assertFalse(
            hasSequencePrivilege(connection, APP, schema + ".grants_default_sequence", "UPDATE"));
      }
      assertFalse(
          hasFunctionPrivilege(connection, APP, "state.grants_default_function()", "EXECUTE"));
      assertFalse(hasPublicFunctionExecute("state.grants_default_function()"));
      assertFalse(
          hasFunctionPrivilege(connection, PURGE, "state.grants_default_function()", "EXECUTE"));
      assertFalse(
          hasFunctionPrivilege(connection, HUMAN, "state.grants_default_function()", "EXECUTE"));
    }
    try (Connection connection = roleConnection(HUMAN)) {
      for (String schema : Set.of("record", "state", "log")) {
        assertRolePrivileges(HUMAN, schema + ".grants_default_table", true, false, false, false);
      }
    }
    try (Connection connection = roleConnection(PURGE)) {
      for (String schema : Set.of("record", "state", "log")) {
        assertRolePrivileges(PURGE, schema + ".grants_default_table", true, false, false, false);
      }
    }
  }

  private static Connection roleConnection(String role) throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, PASSWORD);
  }

  private static Set<String> queryAsAdmin(String sql) throws SQLException {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      try (var result = statement.executeQuery(sql)) {
        var values = new java.util.HashSet<String>();
        while (result.next()) values.add(result.getString(1));
        return values;
      }
    }
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      result.next();
      return result.getString(1);
    }
  }

  private static boolean hasTablePrivilege(
      Connection connection, String role, String table, String privilege) throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_table_privilege('"
                + role
                + "', '"
                + table
                + "', '"
                + privilege
                + "')::text"));
  }

  private static boolean hasSequencePrivilege(
      Connection connection, String role, String sequence, String privilege) throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_sequence_privilege('"
                + role
                + "', '"
                + sequence
                + "', '"
                + privilege
                + "')::text"));
  }

  private static boolean hasFunctionPrivilege(
      Connection connection, String role, String function, String privilege) throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_function_privilege('"
                + role
                + "', '"
                + function
                + "', '"
                + privilege
                + "')::text"));
  }

  private static boolean hasPublicFunctionExecute(String function) throws SQLException {
    try (Connection connection = adminConnection()) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT EXISTS (SELECT 1 FROM pg_proc p, "
                  + "LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a "
                  + "WHERE p.oid = '"
                  + function
                  + "'::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE')::text"));
    }
  }

  private static boolean hasDatabaseCreatePrivilege(String role) throws SQLException {
    try (Connection connection = adminConnection()) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT has_database_privilege('" + role + "', current_database(), 'CREATE')::text"));
    }
  }

  private static void assertPublicSchemaAccessDenied(String role) throws SQLException {
    try (Connection connection = adminConnection()) {
      assertFalse(hasSchemaPrivilege(connection, role, "public", "USAGE"), role + " public USAGE");
      assertFalse(
          hasSchemaPrivilege(connection, role, "public", "CREATE"), role + " public CREATE");
    }
  }

  private static boolean hasSchemaPrivilege(
      Connection connection, String role, String schema, String privilege) throws SQLException {
    return Boolean.parseBoolean(
        scalar(
            connection,
            "SELECT has_schema_privilege('"
                + role
                + "', '"
                + schema
                + "', '"
                + privilege
                + "')::text"));
  }

  private static boolean hasPublicSchemaPrivilege(String privilege) throws SQLException {
    try (Connection connection = adminConnection()) {
      return Boolean.parseBoolean(
          scalar(
              connection,
              "SELECT EXISTS (SELECT 1 FROM pg_namespace n "
                  + "CROSS JOIN LATERAL aclexplode(COALESCE(n.nspacl, acldefault('n', n.nspowner))) a "
                  + "WHERE n.nspname = 'public' AND a.grantee = 0 AND a.privilege_type = '"
                  + privilege
                  + "')::text"));
    }
  }

  private static void assertPermissionDenied(Statement statement, String sql) {
    assertThrows(SQLException.class, () -> statement.execute(sql), sql);
  }
}
