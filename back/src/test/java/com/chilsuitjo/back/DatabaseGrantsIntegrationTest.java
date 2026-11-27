package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chilsuitjo.back.global.config.migration.DatabaseGrants;
import com.chilsuitjo.back.global.config.migration.GrantRoles;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class DatabaseGrantsIntegrationTest {
  private static final String OWNER = "homestamp_owner";
  private static final String MIGRATION = "homestamp_migration";
  private static final String APP = "homestamp_app";
  private static final String PURGE = "homestamp_purge";
  // Human은 NOLOGIN 읽기 그룹(#185). 실제 조회는 그 그룹의 멤버인 사람 계정으로 한다
  private static final String HUMAN = "homestamp_human";
  private static final String HUMAN_PERSON = "homestamp_person_a";
  private static final String INHERITING_MIGRATION = "homestamp_migration_inherit";
  private static final String DECOY_OWNER = "homestamp_decoy_owner";

  /** Human이 컬럼 단위로만 읽는 테이블: 허용 컬럼 / 거부 컬럼(grants.sql과 같은 목록을 독립적으로 적는다). */
  private static final java.util.Map<String, List<List<String>>> HUMAN_COLUMN_ONLY =
      java.util.Map.of(
          "record.cause_note",
          List.of(
              List.of(
                  "id",
                  "room_record_id",
                  "repair_case_id",
                  "side",
                  "text",
                  "basis",
                  "previous_id",
                  "created_at"),
              List.of("expert_name")),
          "state.member",
          List.of(
              List.of(
                  "id",
                  "phone_verified_at",
                  "sms_enabled",
                  "status",
                  "withdrawn_at",
                  "created_at",
                  "version"),
              List.of("email", "password_hash", "display_name", "phone")),
          "state.refresh_token",
          List.of(
              List.of(
                  "id",
                  "member_id",
                  "family_id",
                  "expires_at",
                  "revoked_at",
                  "replaced_by_id",
                  "created_at",
                  "version"),
              List.of("token_hash", "device_label")),
          "state.phone_verification",
          List.of(
              List.of(
                  "id",
                  "purpose",
                  "expires_at",
                  "attempt_count",
                  "verified_at",
                  "consumed_at",
                  "created_at",
                  "version"),
              List.of("phone", "code_hash")),
          "state.link",
          List.of(
              List.of(
                  "id",
                  "room_record_id",
                  "kind",
                  "party_id",
                  "repair_case_id",
                  "inspection_id",
                  "issued_by_member_id",
                  "invalidated_at",
                  "invalidated_reason",
                  "failed_view_count",
                  "created_at",
                  "version"),
              List.of("token_hash")),
          "state.party",
          List.of(
              List.of(
                  "id",
                  "room_record_id",
                  "side",
                  "member_id",
                  "status",
                  "connected_at",
                  "sharing_stopped_at",
                  "created_at",
                  "version"),
              List.of("invite_phone")),
          "state.outbox",
          List.of(
              List.of(
                  "id", "kind", "status", "attempts", "next_attempt_at", "created_at", "version"),
              List.of("payload", "dedupe_key")));

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
          "state.action_chain_head",
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

  private static final GrantRoles ROLES = new GrantRoles(OWNER, APP, PURGE, HUMAN);

  @BeforeAll
  static void migrateAsOwnerThroughMigrationRole() throws Exception {
    POSTGRES.start();
    createRolesAndDatabasePrivileges();

    migrate(POSTGRES.getDatabaseName());
  }

  /** #173 운영 Runner와 같은 Flyway 구성(Migration 로그인 → SET ROLE Owner, schemas 설정 없음). */
  private static void migrate(String db) {
    Flyway.configure()
        .dataSource(url(db), MIGRATION, PASSWORD)
        .initSql("SET ROLE \"" + OWNER + "\"")
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .failOnMissingLocations(true)
        .skipDefaultCallbacks(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  @AfterAll
  static void stopPostgres() {
    assertFalse(POSTGRES.getLogs().contains(PASSWORD), "PG 서버 로그에 비밀번호가 없다");
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
    assertHumanMinimalRead(POSTGRES.getDatabaseName());
    assertExistingFunctionPermissions();
    assertDefaultPrivilegesForNewObjects();
  }

  private static void createRolesAndDatabasePrivileges() throws Exception {
    // #185 infra/db/bootstrap_roles.sql(7fc6d1b)과 같은 역할 속성·멤버십. #185가 병합되면 bootstrap 파일 실행으로 바꾼다
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      String attributes =
          " NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS";
      statement.execute("CREATE ROLE " + OWNER + " NOLOGIN" + attributes);
      for (String login : List.of(MIGRATION, APP, PURGE, HUMAN_PERSON)) {
        statement.execute("CREATE ROLE " + login + " LOGIN" + attributes);
      }
      statement.execute("CREATE ROLE " + HUMAN + " NOLOGIN" + attributes);
      statement.execute(
          "GRANT " + HUMAN + " TO " + HUMAN_PERSON + " WITH ADMIN FALSE, INHERIT TRUE, SET FALSE");
      statement.execute(
          "GRANT " + OWNER + " TO " + MIGRATION + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
      // 잘못된 실행 계정 시험용: Owner를 상속하는 로그인, 객체를 소유하지 않는 가짜 Owner
      statement.execute("CREATE ROLE " + INHERITING_MIGRATION + " LOGIN" + attributes);
      statement.execute(
          "GRANT "
              + OWNER
              + " TO "
              + INHERITING_MIGRATION
              + " WITH ADMIN FALSE, INHERIT TRUE, SET TRUE");
      statement.execute("CREATE ROLE " + DECOY_OWNER + " NOLOGIN" + attributes);
      statement.execute(
          "GRANT "
              + DECOY_OWNER
              + " TO "
              + MIGRATION
              + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
      statement.execute("SET log_statement = 'none'");
      for (String login : List.of(MIGRATION, APP, PURGE, HUMAN_PERSON, INHERITING_MIGRATION)) {
        statement.execute("ALTER ROLE " + login + " PASSWORD '" + PASSWORD + "'");
      }
    }
    prepareDatabasePrivileges(POSTGRES.getDatabaseName());
  }

  /** #185 bootstrap의 DB 단위 선행 권한(DB CREATE·TEMP·CONNECT·public ACL). */
  private static void prepareDatabasePrivileges(String db) throws SQLException {
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("GRANT CREATE ON DATABASE " + db + " TO " + OWNER);
      statement.execute("REVOKE TEMP ON DATABASE " + db + " FROM PUBLIC");
      statement.execute(
          "GRANT CONNECT ON DATABASE "
              + db
              + " TO "
              + String.join(", ", MIGRATION, APP, PURGE, HUMAN_PERSON, INHERITING_MIGRATION));
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
      // 예전 정책(Human 전체 SELECT 기본 권한)이 전역·스키마별로 남아 있어도 지워져야 한다
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT SELECT ON TABLES TO " + HUMAN);
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + OWNER
              + " IN SCHEMA state GRANT SELECT ON TABLES TO "
              + HUMAN);
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

    executeGrantsExpectingFailure();
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

    executeGrantsExpectingFailure();
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
    return adminConnection(POSTGRES.getDatabaseName());
  }

  private static Connection adminConnection(String db) throws SQLException {
    return DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String url(String db) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + db;
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
    try (Connection connection = roleConnection(MIGRATION)) {
      DatabaseGrants.apply(connection, ROLES);
    }
  }

  /** 실패해야 하는 GRANT 실행. 실패 원인을 돌려준다(비밀번호가 없어야 한다). */
  private static SQLException executeGrantsExpectingFailure() throws Exception {
    try (Connection connection = roleConnection(MIGRATION)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, ROLES));
      assertFalse(chain(failure).contains(PASSWORD), "실패 메시지에 비밀번호가 없다");
      return failure;
    }
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
      assertRolePrivileges(
          HUMAN, table, !HUMAN_COLUMN_ONLY.containsKey(table), false, false, false);
    }
    for (String table : STATE_TABLES) {
      // 민감 테이블은 테이블 단위 SELECT가 없다(컬럼 단위만, assertHumanMinimalRead에서 실제 로그인으로 검사)
      boolean lookup = table.equals("state.part_template") || table.equals("state.region");
      boolean optOut = table.equals("state.sms_opt_out");
      // 호실 행·체인 머리는 고치기만 하고 앱이 지우지 않는다(체인 머리는 #128, 지우면 번호를 다시 쓸 수 있음)
      boolean noDelete =
          table.equals("state.room_record") || table.equals("state.action_chain_head");
      assertRolePrivileges(APP, table, true, !lookup, !lookup && !optOut, !lookup && !noDelete);
      assertRolePrivileges(PURGE, table, true, table.equals("state.outbox"), false, false);
      assertRolePrivileges(
          HUMAN, table, !HUMAN_COLUMN_ONLY.containsKey(table), false, false, false);
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
    // 사람 계정은 그룹 권한을 상속해 읽는다(SET ROLE 없이)
    try (Connection connection = roleConnection(HUMAN_PERSON);
        Statement statement = connection.createStatement()) {
      statement.execute("SELECT id, status FROM state.member");
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
    // 새 테이블은 Human에게 아무 권한이 없다(grants.sql 허용 목록에 넣어야 열림). 실제 로그인으로도 거부된다
    try (Connection connection = roleConnection(HUMAN_PERSON);
        Statement statement = connection.createStatement()) {
      for (String schema : Set.of("record", "state", "log")) {
        assertRolePrivileges(HUMAN, schema + ".grants_default_table", false, false, false, false);
        assertFalse(
            Boolean.parseBoolean(
                scalar(
                    connection,
                    "SELECT has_any_column_privilege('"
                        + HUMAN
                        + "', '"
                        + schema
                        + ".grants_default_table', 'SELECT')::text")));
        assertPermissionDenied(statement, "SELECT value FROM " + schema + ".grants_default_table");
      }
    }
    try (Connection connection = roleConnection(PURGE)) {
      for (String schema : Set.of("record", "state", "log")) {
        assertRolePrivileges(PURGE, schema + ".grants_default_table", true, false, false, false);
      }
    }
  }

  private static Connection roleConnection(String role) throws SQLException {
    return roleConnection(POSTGRES.getDatabaseName(), role);
  }

  private static Connection roleConnection(String db, String role) throws SQLException {
    return DriverManager.getConnection(url(db), role, PASSWORD);
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

  /** PostgreSQL insufficient_privilege. 없는 컬럼(42703)·테이블(42P01)·문법 오류(42601) 등은 권한 거부로 인정하지 않는다. */
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  private static void assertPermissionDenied(Statement statement, String sql) {
    SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
    assertEquals(
        INSUFFICIENT_PRIVILEGE,
        sqlState(failure),
        "권한 거부여야 한다: " + sql + " / " + failure.getMessage());
  }

  /** 감싼 예외까지 따라가 처음 나오는 SQLState. */
  private static String sqlState(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  @Test
  void permissionDeniedHelperDoesNotAcceptOtherErrorsOrSuccess() throws Exception {
    try (Connection connection = roleConnection(HUMAN_PERSON);
        Statement statement = connection.createStatement()) {
      for (String wrong :
          List.of(
              "SELECT no_such_column FROM state.notice", // 42703
              "SELECT 1 FROM state.no_such_table", // 42P01
              "SELEC 1", // 42601
              "SELECT id FROM state.notice")) { // 성공
        assertThrows(
            org.opentest4j.AssertionFailedError.class,
            () -> assertPermissionDenied(statement, wrong),
            wrong);
      }
      SQLException missingColumn =
          assertThrows(
              SQLException.class,
              () -> statement.execute("SELECT no_such_column FROM state.notice"));
      assertEquals("42703", sqlState(missingColumn));
      assertPermissionDenied(statement, "SELECT password_hash FROM state.member");
    }
  }

  // ======================================================== JDBC 실행 계약(트랜잭션·역할·잠금·롤백)

  /** 테스트마다 새 DB: #185 선행 권한 → #173과 같은 Flyway(V1~V13). 역할은 클러스터 전체라 한 번만 만든다. */
  private static String freshDatabase(String db) throws Exception {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + db);
    }
    prepareDatabasePrivileges(db);
    migrate(db);
    assertEquals(
        "13",
        scalarAsAdmin(
            db,
            "SELECT max(version::int)::text FROM public.flyway_schema_history WHERE success AND version IS NOT NULL"),
        "이 테스트가 쓰는 DB 버전은 V13");
    return db;
  }

  @Test
  void migrationLoginCanOnlySwitchToOwnerAndNeverUsesItWithoutSetRole() throws Exception {
    String db = freshDatabase("grants_role_semantics");
    try (Connection connection = adminConnection(db)) {
      // PostgreSQL 18: MEMBER=멤버십 있음, SET=전환 가능, USAGE=전환 없이 권한 사용(상속)
      assertEquals(
          "true|true|false|false",
          scalar(
              connection,
              "SELECT pg_has_role('"
                  + MIGRATION
                  + "','"
                  + OWNER
                  + "','MEMBER')||'|'||pg_has_role('"
                  + MIGRATION
                  + "','"
                  + OWNER
                  + "','SET')||'|'||pg_has_role('"
                  + MIGRATION
                  + "','"
                  + OWNER
                  + "','USAGE')||'|'||pg_has_role('"
                  + MIGRATION
                  + "','"
                  + OWNER
                  + "','MEMBER WITH ADMIN OPTION')"));
      assertEquals(
          "true",
          scalar(
              connection,
              "SELECT pg_has_role('" + INHERITING_MIGRATION + "','" + OWNER + "','USAGE')::text"));
    }
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      assertPermissionDenied(statement, "SELECT count(*) FROM record.action");
      assertPermissionDenied(statement, "CREATE TABLE record.migration_without_set_role (id int)");
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL ROLE \"" + OWNER + "\"");
      statement.execute("SELECT count(*) FROM record.action");
      connection.rollback();
      assertEquals(MIGRATION, scalar(connection, "SELECT current_user"));
    }
  }

  @Test
  void jdbcGrantsUseOneConnectionOneTransactionAndLeaveNoSessionState() throws Exception {
    String db = freshDatabase("grants_session");
    // 테스트 전용 관찰: 관리자가 만든 event trigger가 권한 변경 문장마다 그 순간의 실행 상태를 기록한다(운영 코드에 훅 없음).
    // 함수는 호출자 권한으로 돌므로 current_user는 GRANT를 실행한 역할이다
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA hsobserve");
      statement.execute("CREATE TABLE hsobserve.seen (state text NOT NULL)");
      statement.execute("GRANT USAGE ON SCHEMA hsobserve TO " + OWNER);
      statement.execute("GRANT INSERT ON hsobserve.seen TO " + OWNER);
      statement.execute(
          "CREATE FUNCTION hsobserve.record_state() RETURNS event_trigger LANGUAGE plpgsql"
              + " SET search_path = pg_catalog AS $$ BEGIN INSERT INTO hsobserve.seen VALUES ("
              + "session_user||'|'||current_user||'|'||pg_backend_pid()||'|'"
              + "||current_setting('homestamp.grants.app_role')||'|'"
              + "||(SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid()"
              + " AND classid=1752392040 AND objid=156 AND objsubid=2 AND granted)); END $$");
      statement.execute(
          "CREATE EVENT TRIGGER hsobserve_privilege_change ON ddl_command_end"
              + " WHEN TAG IN ('GRANT', 'REVOKE', 'ALTER DEFAULT PRIVILEGES')"
              + " EXECUTE FUNCTION hsobserve.record_state()");
    }
    try (Connection connection = roleConnection(db, MIGRATION)) {
      int pid = Integer.parseInt(scalar(connection, "SELECT pg_backend_pid()::text"));
      DatabaseGrants.Observed observed = DatabaseGrants.apply(connection, ROLES);
      List<String> insideTransaction = new ArrayList<>();
      try (Connection admin = adminConnection(db);
          Statement statement = admin.createStatement();
          ResultSet rows = statement.executeQuery("SELECT DISTINCT state FROM hsobserve.seen")) {
        while (rows.next()) {
          insideTransaction.add(rows.getString(1));
        }
        statement.execute("DROP EVENT TRIGGER hsobserve_privilege_change");
      }
      assertEquals(new DatabaseGrants.Observed(MIGRATION, OWNER, pid), observed);
      // DO 블록 직후에도 같은 연결·같은 트랜잭션: Owner 유지, 지역 설정 유지, 잠금 보유
      assertEquals(
          List.of(MIGRATION + "|" + OWNER + "|" + pid + "|" + APP + "|1"), insideTransaction);
      // COMMIT 뒤: SET LOCAL ROLE·set_config(is_local=true)·xact 잠금이 모두 사라진다
      assertTrue(connection.getAutoCommit(), "자동 커밋 설정을 원래대로 돌린다");
      assertEquals(
          MIGRATION + "|" + MIGRATION + "||0",
          scalar(
              connection,
              "SELECT session_user||'|'||current_user||'|'||coalesce(current_setting('homestamp.grants.app_role', true),'')"
                  + "||'|'||(SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND pid=pg_backend_pid())"));
    }
    try (Connection connection = adminConnection(db)) {
      assertTrue(hasTablePrivilege(connection, APP, "record.action", "INSERT"));
    }
  }

  @Test
  void rerunIsIdenticalAndRemovesOverbroadDirectGrants() throws Exception {
    String db = freshDatabase("grants_rerun");
    applyGrants(db);
    List<String> first = aclSnapshot(db);
    applyGrants(db);
    assertEquals(first, aclSnapshot(db), "두 번째 실행 결과가 같다(Default Privileges 포함)");

    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("GRANT UPDATE, DELETE, TRUNCATE ON record.action TO " + APP);
      statement.execute("GRANT DELETE ON state.room_record TO " + APP);
      statement.execute("GRANT EXECUTE ON FUNCTION state.purge_room_record(uuid) TO " + APP);
      statement.execute("GRANT INSERT ON record.action TO " + HUMAN);
      statement.execute("GRANT SELECT ON state.member TO " + HUMAN);
      statement.execute("GRANT SELECT (password_hash, phone) ON state.member TO " + HUMAN);
      statement.execute("GRANT SELECT (token_hash) ON state.refresh_token TO PUBLIC");
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT SELECT ON TABLES TO " + HUMAN);
      statement.execute("GRANT SELECT ON public.flyway_schema_history TO PUBLIC");
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE "
              + OWNER
              + " IN SCHEMA record GRANT UPDATE ON TABLES TO "
              + APP);
    }
    try (Connection connection = adminConnection(db)) {
      assertTrue(hasTablePrivilege(connection, APP, "record.action", "UPDATE"));
    }
    assertFalse(first.equals(aclSnapshot(db)));
    applyGrants(db);
    assertEquals(first, aclSnapshot(db), "과잉 권한을 모두 회수하고 같은 상태로 돌아간다");
    try (Connection connection = roleConnection(db, APP);
        Statement statement = connection.createStatement()) {
      assertPermissionDenied(statement, "UPDATE record.action SET created_at = created_at");
      assertPermissionDenied(statement, "DELETE FROM state.room_record");
      assertPermissionDenied(statement, "TRUNCATE record.action");
    }
    assertHumanMinimalRead(db);
  }

  @Test
  void everyFailureRollsBackWithoutChangingPrivileges() throws Exception {
    String db = freshDatabase("grants_failures");
    applyGrants(db);
    // 실패 전 상태를 알아볼 수 있게 과잉 권한 하나를 남겨 둔다. 실패한 실행이 이것을 지우면 롤백되지 않은 것이다
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("GRANT UPDATE ON record.action TO " + APP);
    }
    List<String> before = aclSnapshot(db);

    // 필수 테이블 누락(이름 변경으로 재현)
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("ALTER TABLE state.sms_opt_out RENAME TO sms_opt_out_renamed");
    }
    // 이름 변경 자체가 스냅샷을 바꾸므로 변경 뒤 상태를 기준으로 비교한다
    assertFailsAndUnchanged(
        db,
        aclSnapshot(db),
        MIGRATION,
        ROLES,
        "C-table UPDATE/DELETE grants in db/grants/grants.sql together");
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("ALTER TABLE state.sms_opt_out_renamed RENAME TO sms_opt_out");
    }
    assertEquals(before, aclSnapshot(db));
    // Human 자리에 LOGIN 역할(사람 계정)을 넣으면 그룹이 아니므로 실패하고 아무것도 바뀌지 않는다
    assertFailsAndUnchanged(
        db,
        before,
        MIGRATION,
        new GrantRoles(OWNER, APP, PURGE, HUMAN_PERSON),
        "human role must be a NOLOGIN read group");
    // 역할 이름: 없는 역할, 겹치는 역할
    assertFailsAndUnchanged(
        db,
        before,
        MIGRATION,
        new GrantRoles(OWNER, "homestamp_missing", PURGE, HUMAN),
        "must exist");
    // 겹치는 이름은 운영 실행기가 DB에 닿기 전에 거절한다. SQL 자체의 검사도 직접 실행해 확인한다
    assertThrows(IllegalStateException.class, () -> new GrantRoles(OWNER, APP, APP, HUMAN));
    assertRawGrantsFail(db, APP, APP, HUMAN, "must be distinct");
    assertEquals(before, aclSnapshot(db));
    // Owner로 전환할 수 없는 로그인(App), 객체를 소유하지 않는 Owner, Owner를 상속하는 Migration
    assertFailsAndUnchanged(db, before, APP, ROLES, "permission denied to set role");
    assertFailsAndUnchanged(
        db,
        before,
        MIGRATION,
        new GrantRoles(DECOY_OWNER, APP, PURGE, HUMAN),
        "must be owned by the current owner role");
    assertFailsAndUnchanged(db, before, INHERITING_MIGRATION, ROLES, "must not inherit");
    // COMMIT 시점 오류: 관리자가 만든 지연 제약 trigger가 COMMIT 때 실패한다(운영 코드에 훅 없음).
    // event trigger가 권한 변경 중에 표시 행을 넣고, 지연 trigger는 그 행을 보고 COMMIT에서 예외를 낸다
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA hscommit");
      statement.execute("CREATE TABLE hscommit.mark (id int)");
      statement.execute(
          "CREATE FUNCTION hscommit.fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$"
              + " BEGIN RAISE EXCEPTION 'injected commit failure'; END $$");
      statement.execute(
          "CREATE CONSTRAINT TRIGGER hscommit_fail AFTER INSERT ON hscommit.mark"
              + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hscommit.fail_at_commit()");
      statement.execute(
          "CREATE FUNCTION hscommit.mark_once() RETURNS event_trigger LANGUAGE plpgsql"
              + " SECURITY DEFINER SET search_path = pg_catalog AS $$ BEGIN"
              + " INSERT INTO hscommit.mark SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM hscommit.mark); END $$");
      statement.execute(
          "CREATE EVENT TRIGGER hscommit_mark ON ddl_command_end"
              + " WHEN TAG IN ('GRANT', 'REVOKE', 'ALTER DEFAULT PRIVILEGES')"
              + " EXECUTE FUNCTION hscommit.mark_once()");
    }
    try (Connection connection = roleConnection(db, MIGRATION)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, ROLES));
      assertTrue(chain(failure).contains("injected commit failure"), chain(failure));
      assertConnectionReset(connection);
    }
    assertEquals("0", scalarAsAdmin(db, "SELECT count(*)::text FROM hscommit.mark"));
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP EVENT TRIGGER hscommit_mark");
      statement.execute("DROP SCHEMA hscommit CASCADE");
    }
    assertEquals(before, aclSnapshot(db), "COMMIT 전 오류도 전부 되돌린다");
  }

  @Test
  void failureInTheMiddleOfPrivilegeChangesRollsBackEverything() throws Exception {
    String db = freshDatabase("grants_mid_failure");
    applyGrants(db);
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("GRANT UPDATE ON record.action TO " + APP);
    }
    // 테스트 전용 오류 주입: 관리자 소유 event trigger가 GRANT·REVOKE·ALTER DEFAULT PRIVILEGES를 세다가 N번째에서 실패.
    // 운영 SQL은 바꾸지 않는다. 카운터도 같은 트랜잭션에 있어 실패하면 함께 되돌아간다
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA hsfault");
      statement.execute("CREATE TABLE hsfault.counter (seen int NOT NULL, fail_at int NOT NULL)");
      statement.execute("INSERT INTO hsfault.counter VALUES (0, 0)");
      statement.execute(
          "CREATE FUNCTION hsfault.count_privilege_change() RETURNS event_trigger LANGUAGE plpgsql"
              + " SECURITY DEFINER SET search_path = pg_catalog AS $$"
              + " DECLARE n int; f int;"
              + " BEGIN UPDATE hsfault.counter SET seen = seen + 1 RETURNING seen, fail_at INTO n, f;"
              + " IF n = f THEN RAISE EXCEPTION 'injected grants failure at privilege change %', n; END IF; END $$");
      statement.execute(
          "CREATE EVENT TRIGGER hsfault_privilege_change ON ddl_command_end"
              + " WHEN TAG IN ('GRANT', 'REVOKE', 'ALTER DEFAULT PRIVILEGES')"
              + " EXECUTE FUNCTION hsfault.count_privilege_change()");
    }
    // 한 번 끝까지 실행해 권한 변경 문장 수를 센다(fail_at=0이면 실패하지 않음). 그 결과로 기준 상태를 다시 만든다
    applyGrants(db);
    int total = Integer.parseInt(scalarAsAdmin(db, "SELECT seen::text FROM hsfault.counter"));
    assertTrue(total > 40, "권한 변경 문장 수: " + total);
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("GRANT UPDATE ON record.action TO " + APP);
    }
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE hsfault.counter SET seen = 0, fail_at = " + (total / 2));
    }
    List<String> before = aclSnapshot(db);
    try (Connection connection = roleConnection(db, MIGRATION)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, ROLES));
      assertTrue(
          chain(failure).contains("injected grants failure at privilege change " + (total / 2)),
          chain(failure));
      assertConnectionReset(connection);
    }
    assertEquals(before, aclSnapshot(db), "앞의 " + (total / 2 - 1) + "개 변경까지 모두 되돌린다");
    assertEquals("0", scalarAsAdmin(db, "SELECT seen::text FROM hsfault.counter"));
    try (Connection connection = adminConnection(db)) {
      assertTrue(hasTablePrivilege(connection, APP, "record.action", "UPDATE"), "실패 전 상태 그대로");
    }
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP EVENT TRIGGER hsfault_privilege_change");
      statement.execute("DROP SCHEMA hsfault CASCADE");
    }
  }

  @Test
  void laterColumnsNeverBecomeVisibleToHumanWithoutReview() throws Exception {
    String db = freshDatabase("grants_human_columns");
    applyGrants(db);
    // 민감 테이블에 새 컬럼: 컬럼 단위 허용 목록에 없으니 Human에게 열리지 않는다
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("ALTER TABLE state.member ADD COLUMN recovery_contact varchar(50)");
    }
    applyGrants(db);
    try (Connection connection = roleConnection(db, HUMAN_PERSON);
        Statement statement = connection.createStatement()) {
      assertPermissionDenied(statement, "SELECT recovery_contact FROM state.member");
      statement.execute("SELECT id, status FROM state.member");
    }
    // 테이블 단위로 열린 테이블에 민감해 보이는 컬럼: 검토 없이는 GRANT 전체가 실패하고 권한은 그대로
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("ALTER TABLE state.notice ADD COLUMN recipient_phone varchar(20)");
    }
    List<String> before = aclSnapshot(db);
    try (Connection connection = roleConnection(db, MIGRATION)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, ROLES));
      assertTrue(
          chain(failure)
              .contains("unapproved sensitive-looking columns: {state.notice.recipient_phone}"),
          chain(failure));
    }
    assertEquals(before, aclSnapshot(db));
  }

  @Test
  void sqlRefusesToRunWithoutSetRoleLockOrExplicitTransaction() throws Exception {
    String db = freshDatabase("grants_raw");
    List<String> before = aclSnapshot(db);
    String sql = DatabaseGrants.sql();
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      // 1) SET ROLE 없음
      connection.setAutoCommit(false);
      setRoles(connection);
      statement.execute("SELECT pg_advisory_xact_lock(1752392040, 156)");
      assertTrue(
          assertThrows(SQLException.class, () -> statement.execute(sql))
              .getMessage()
              .contains("after SET ROLE"));
      connection.rollback();
      // 2) 잠금 없음
      statement.execute("SET LOCAL ROLE \"" + OWNER + "\"");
      setRoles(connection);
      assertTrue(
          assertThrows(SQLException.class, () -> statement.execute(sql))
              .getMessage()
              .contains("advisory lock"));
      connection.rollback();
      // 3) 자동 커밋: 앞 문장에서 잡은 xact 잠금은 그 문장이 끝나면 풀린다
      connection.setAutoCommit(true);
      statement.execute("SET ROLE \"" + OWNER + "\"");
      statement.execute("SELECT pg_advisory_xact_lock(1752392040, 156)");
      assertTrue(
          assertThrows(SQLException.class, () -> statement.execute(sql))
              .getMessage()
              .contains("advisory lock"));
    }
    assertEquals(before, aclSnapshot(db));
  }

  @Test
  void advisoryLockSerializesConcurrentGrantRuns() throws Exception {
    String db = freshDatabase("grants_lock");
    try (Connection holder = roleConnection(db, MIGRATION);
        Connection runner = roleConnection(db, MIGRATION);
        Statement statement = holder.createStatement()) {
      int runnerPid = Integer.parseInt(scalar(runner, "SELECT pg_backend_pid()::text"));
      holder.setAutoCommit(false);
      statement.execute("SELECT pg_advisory_xact_lock(1752392040, 156)");
      CompletableFuture<DatabaseGrants.Observed> run =
          CompletableFuture.supplyAsync(() -> {
            try {
              return DatabaseGrants.apply(runner, ROLES);
            } catch (SQLException exception) {
              throw new IllegalStateException(exception);
            }
          });
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (!"1"
          .equals(
              scalarAsAdmin(
                  db,
                  "SELECT count(*)::text FROM pg_locks WHERE locktype='advisory' AND NOT granted AND pid="
                      + runnerPid))) {
        assertTrue(System.nanoTime() < deadline, "두 번째 실행이 잠금을 기다려야 한다");
        Thread.sleep(50);
      }
      assertFalse(run.isDone());
      try (Connection connection = adminConnection(db)) {
        assertFalse(
            hasTablePrivilege(connection, APP, "record.action", "INSERT"),
            "잠금을 기다리는 동안 아무것도 바뀌지 않는다");
      }
      holder.commit();
      assertEquals(OWNER, run.get(30, TimeUnit.SECONDS).currentUser());
    }
    try (Connection connection = adminConnection(db)) {
      assertTrue(hasTablePrivilege(connection, APP, "record.action", "INSERT"));
    }
  }

  @Test
  void executorRejectsUnsafeIdentifiersBeforeTouchingTheDatabase() throws Exception {
    for (String bad :
        new String[] {
          "homestamp_app; DROP", "Homestamp_app", "pg_signal_backend", "a\"b", "", "x".repeat(64)
        }) {
      assertThrows(IllegalStateException.class, () -> new GrantRoles(OWNER, bad, PURGE, HUMAN));
      assertThrows(IllegalStateException.class, () -> new GrantRoles(bad, APP, PURGE, HUMAN));
      assertThrows(IllegalStateException.class, () -> new GrantRoles(OWNER, APP, PURGE, bad));
    }
    assertThrows(IllegalStateException.class, () -> new GrantRoles(OWNER, APP, PURGE, null));
  }

  /**
   * 실제 Human 로그인으로: 허용 컬럼은 읽히고, 금지 컬럼은 SELECT 목록·WHERE·SELECT * 어디에 써도 거부. 쓰기·TRUNCATE·SET ROLE 거부.
   * PUBLIC·다른 역할 멤버십을 통한 간접 권한도 없다.
   */
  private static void assertHumanMinimalRead(String db) throws SQLException {
    UUID member = UUID.randomUUID();
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO state.member (id, email, password_hash, display_name, phone, status, created_at, version)"
              + " VALUES ('"
              + member
              + "', 'human-test@example.invalid', 'HUMAN-TEST-HASH', 'n', '01000000000', 'ACTIVE', now(), 0)");
    }
    try (Connection connection = roleConnection(db, HUMAN_PERSON);
        Statement statement = connection.createStatement()) {
      for (var entry : HUMAN_COLUMN_ONLY.entrySet()) {
        String table = entry.getKey();
        statement.execute(
            "SELECT " + String.join(", ", entry.getValue().get(0)) + " FROM " + table);
        statement.execute("SELECT count(id) FROM " + table);
        assertPermissionDenied(statement, "SELECT * FROM " + table);
        for (String column : entry.getValue().get(1)) {
          assertPermissionDenied(statement, "SELECT " + column + " FROM " + table);
          assertPermissionDenied(
              statement, "SELECT id FROM " + table + " WHERE " + column + " IS NOT NULL");
          assertPermissionDenied(statement, "SELECT t FROM " + table + " t");
        }
        assertPermissionDenied(statement, "UPDATE " + table + " SET id = id");
        assertPermissionDenied(
            statement, "INSERT INTO " + table + " (id) SELECT id FROM " + table + " WHERE false");
        assertPermissionDenied(statement, "DELETE FROM " + table);
        assertPermissionDenied(statement, "TRUNCATE " + table);
      }
      assertEquals(
          "ACTIVE",
          scalar(connection, "SELECT status FROM state.member WHERE id = '" + member + "'"));
      // 테이블 단위 허용 목록은 그대로 읽힌다(장애 대응의 기록·상태 조회)
      for (String table :
          List.of(
              "record.action",
              "state.room_record",
              "state.notice",
              "state.repair_case",
              "log.deletion_log")) {
        statement.execute("SELECT * FROM " + table);
      }
      assertPermissionDenied(statement, "INSERT INTO state.notice (id) VALUES (uuidv7())");
      assertPermissionDenied(statement, "SET ROLE " + OWNER);
      assertPermissionDenied(statement, "SET ROLE " + APP);
      assertPermissionDenied(statement, "SET ROLE " + PURGE);
    }
    try (Connection connection = adminConnection(db)) {
      // 간접 경로: Human의 역할 멤버십 없음, PUBLIC의 테이블·컬럼 권한 없음, 금지 컬럼 권한 없음
      assertEquals(
          "0",
          scalar(
              connection,
              "SELECT count(*)::text FROM pg_auth_members WHERE member = '"
                  + HUMAN
                  + "'::regrole"));
      for (var entry : HUMAN_COLUMN_ONLY.entrySet()) {
        String table = entry.getKey();
        assertEquals(
            "0",
            scalar(
                connection,
                "SELECT (SELECT count(*) FROM pg_class c, LATERAL aclexplode(c.relacl) a WHERE c.oid = '"
                    + table
                    + "'::regclass AND a.grantee = 0) + (SELECT count(*) FROM pg_attribute t, LATERAL aclexplode(t.attacl) a"
                    + " WHERE t.attrelid = '"
                    + table
                    + "'::regclass AND a.grantee = 0)"),
            "PUBLIC 권한 없음: " + table);
        for (String column : entry.getValue().get(1)) {
          assertEquals(
              "false",
              scalar(
                  connection,
                  "SELECT has_column_privilege('"
                      + HUMAN
                      + "', '"
                      + table
                      + "', '"
                      + column
                      + "', 'SELECT')::text"),
              HUMAN + " " + table + "." + column);
        }
      }
    }
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM state.member WHERE id = '" + member + "'");
    }
  }

  private static void applyGrants(String db) throws Exception {
    try (Connection connection = roleConnection(db, MIGRATION)) {
      DatabaseGrants.apply(connection, ROLES);
    }
  }

  private static void setRoles(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config(?, ?, true)")) {
      for (String[] pair :
          new String[][] {{"app_role", APP}, {"purge_role", PURGE}, {"human_role", HUMAN}}) {
        statement.setString(1, "homestamp.grants." + pair[0]);
        statement.setString(2, pair[1]);
        statement.execute();
      }
    }
  }

  /** 운영 실행기를 거치지 않고 SQL만 같은 순서로 실행해 SQL 자체의 사전 검사를 본다. */
  private static void assertRawGrantsFail(
      String db, String app, String purge, String human, String reason) throws Exception {
    try (Connection connection = roleConnection(db, MIGRATION);
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL ROLE \"" + OWNER + "\"");
      try (PreparedStatement config =
          connection.prepareStatement("SELECT set_config(?, ?, true)")) {
        for (String[] pair :
            new String[][] {{"app_role", app}, {"purge_role", purge}, {"human_role", human}}) {
          config.setString(1, "homestamp.grants." + pair[0]);
          config.setString(2, pair[1]);
          config.execute();
        }
      }
      statement.execute("SELECT pg_advisory_xact_lock(1752392040, 156)");
      SQLException failure =
          assertThrows(SQLException.class, () -> statement.execute(DatabaseGrants.sql()));
      assertTrue(chain(failure).contains(reason), reason + " / " + chain(failure));
      connection.rollback();
    }
  }

  private static void assertFailsAndUnchanged(
      String db, List<String> before, String login, GrantRoles roles, String reason)
      throws Exception {
    try (Connection connection = roleConnection(db, login)) {
      SQLException failure =
          assertThrows(SQLException.class, () -> DatabaseGrants.apply(connection, roles));
      assertTrue(chain(failure).contains(reason), reason + " / " + chain(failure));
      assertFalse(chain(failure).contains(PASSWORD));
      assertConnectionReset(connection);
    }
    assertEquals(before, aclSnapshot(db), "실패(" + reason + ") 뒤 권한이 그대로다");
  }

  /** 실패 뒤 같은 연결: 롤백됐고(Owner 아님, 지역 설정 없음) 자동 커밋이 돌아왔고 다시 쓸 수 있다. */
  private static void assertConnectionReset(Connection connection) throws SQLException {
    assertTrue(connection.getAutoCommit());
    assertEquals(
        "|" + connection.getMetaData().getUserName(),
        scalar(
            connection,
            "SELECT coalesce(current_setting('homestamp.grants.app_role', true),'')||'|'||current_user"));
  }

  /** 권한 상태 전체: 관리 스키마·public의 테이블·시퀀스·함수·스키마 ACL, Default Privileges, DB ACL. aclitem은 정렬해 비교. */
  private static List<String> aclSnapshot(String db) throws SQLException {
    String acl =
        "(SELECT coalesce(string_agg(a::text, ',' ORDER BY a::text), '<default>') FROM unnest(%s) a)";
    String sql =
        "SELECT 'rel ' || c.oid::regclass || ' ' || "
            + acl.formatted("c.relacl")
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname IN ('record','state','log','public') AND c.relkind IN ('r','p','S','v','m')"
            + " UNION ALL SELECT 'col ' || a.attrelid::regclass || '.' || a.attname || ' ' || "
            + acl.formatted("a.attacl")
            + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname IN ('record','state','log','public') AND a.attnum > 0 AND NOT a.attisdropped AND a.attacl IS NOT NULL"
            + " UNION ALL SELECT 'fn ' || p.oid::regprocedure || ' ' || "
            + acl.formatted("p.proacl")
            + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN ('record','state','log','public')"
            + " UNION ALL SELECT 'nsp ' || nspname || ' ' || "
            + acl.formatted("nspacl")
            + " FROM pg_namespace WHERE nspname IN ('record','state','log','public')"
            + " UNION ALL SELECT 'default ' || defaclrole::regrole || ' ' || coalesce(nullif(defaclnamespace, 0)::regnamespace::text, '*')"
            + " || ' ' || defaclobjtype::text || ' ' || "
            + acl.formatted("defaclacl")
            + " FROM pg_default_acl"
            + " UNION ALL SELECT 'db ' || "
            + acl.formatted("datacl")
            + " FROM pg_database WHERE datname = current_database()"
            + " ORDER BY 1";
    List<String> rows = new ArrayList<>();
    try (Connection connection = adminConnection(db);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        rows.add(result.getString(1));
      }
    }
    assertTrue(rows.size() > 40, "스냅샷이 비어 있지 않다");
    return rows;
  }

  private static String scalarAsAdmin(String db, String sql) throws SQLException {
    try (Connection connection = adminConnection(db)) {
      return scalar(connection, sql);
    }
  }

  private static String chain(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      text.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" > ");
    }
    return text.toString();
  }
}
