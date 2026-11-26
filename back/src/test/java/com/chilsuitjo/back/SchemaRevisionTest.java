package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

class SchemaRevisionTest extends SchemaTestSupport {
  @Test
  void tablesAndHistoryAreInTheirAssignedSchemas() {
    assertEquals(12L, tableCount("record")); // V8 record.supplement
    assertEquals(26L, tableCount("state")); // V10 state.action_chain_head
    assertEquals(1L, tableCount("log"));
    assertEquals(1L, tableCount("public"));
    assertEquals(
        "public",
        jdbc.queryForObject(
            "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = 'public.flyway_schema_history'::regclass",
            String.class));
  }

  @Test
  void appCanReleaseOptOutButHasNoRecordDeleteOrAuditReadPermission() {
    UUID member = member("ACTIVE");
    acknowledgement(member);
    jdbc.update("INSERT INTO state.sms_opt_out VALUES ('test-phone-hmac', CURRENT_TIMESTAMP)");
    String app = appRole();
    jdbc.execute("SET LOCAL ROLE " + app);
    assertEquals(
        1, jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = 'test-phone-hmac'"));
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_table_privilege(?, 'record.member_acknowledgement', 'DELETE')",
            Boolean.class,
            app));
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_table_privilege(?, 'log.deletion_log', 'SELECT')", Boolean.class, app));
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_function_privilege(?, 'state.purge_room_record(uuid)', 'EXECUTE')",
            Boolean.class,
            app));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM record.member_acknowledgement WHERE member_id = ?", member));
  }

  @Test
  void optOutCannotBeChangedInPlace() {
    jdbc.update("INSERT INTO state.sms_opt_out VALUES ('test-phone-hmac', CURRENT_TIMESTAMP)");
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE state.sms_opt_out SET phone_hmac = 'another'"));
  }

  @Test
  void withdrawnAcknowledgementsArePurgedWithAuditAndOutboxOnlyForThatMember() {
    UUID member = member("WITHDRAWN");
    UUID ack = acknowledgement(member);
    UUID other = member("ACTIVE");
    acknowledgement(other);
    String app = appRole();
    jdbc.execute(
        "GRANT EXECUTE ON FUNCTION record.purge_member_acknowledgements(uuid, timestamptz) TO "
            + app);
    jdbc.execute("SET LOCAL app.purge = 'off'");
    jdbc.execute("SET LOCAL ROLE " + app);
    assertEquals(1, purgeAcknowledgements(member));
    assertEquals(0, purgeAcknowledgements(member));
    assertEquals("off", jdbc.queryForObject("SELECT current_setting('app.purge')", String.class));
    jdbc.execute("RESET ROLE");
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM record.member_acknowledgement WHERE member_id = ?",
            Long.class,
            member));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM record.member_acknowledgement WHERE member_id = ?",
            Long.class,
            other));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM log.deletion_log WHERE target_id = ? AND reason = 'WITHDRAWAL'",
            Long.class,
            ack));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM state.outbox o JOIN log.deletion_log d ON o.payload->>'deletionLogId' = d.id::text WHERE d.target_id = ? AND o.kind = 'AUDIT_WRITE'",
            Long.class,
            ack));
  }

  @Test
  void activeMemberAcknowledgementsCannotBePurged() {
    UUID member = member("ACTIVE");
    acknowledgement(member);
    assertThrows(DataAccessException.class, () -> purgeAcknowledgements(member));
  }

  @Test
  void acknowledgementFunctionIsPrivateByDefault() {
    String app = appRole();
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_function_privilege(?, 'record.purge_member_acknowledgements(uuid,timestamptz)', 'EXECUTE')",
            Boolean.class,
            app));
  }

  @Test
  void acknowledgementDeletionRollsBackIfOutboxInsertFails() {
    UUID member = member("WITHDRAWN");
    UUID ack = acknowledgement(member);
    jdbc.execute(
        "CREATE FUNCTION pg_temp.reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox unavailable'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_audit BEFORE INSERT ON state.outbox FOR EACH ROW EXECUTE FUNCTION pg_temp.reject_audit()");
    jdbc.execute("SAVEPOINT before_purge");
    assertThrows(DataAccessException.class, () -> purgeAcknowledgements(member));
    jdbc.execute("ROLLBACK TO SAVEPOINT before_purge");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM record.member_acknowledgement WHERE id = ?", Long.class, ack));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM log.deletion_log WHERE target_id = ?", Long.class, ack));
  }

  @Test
  void causeNoteCannotHaveTwoSuccessors() {
    UUID room = room();
    UUID repair = repair(room);
    UUID previous = cause(room, repair, null);
    cause(room, repair, previous);
    assertThrows(DataAccessException.class, () -> cause(room, repair, previous));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"NULL, 'CONNECTED'", "CURRENT_TIMESTAMP, NULL", "CURRENT_TIMESTAMP, 'UNKNOWN'"})
  void invalidLinkStateIsRejected(String values) {
    UUID link = link();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE state.link SET (invalidated_at, invalidated_reason) = ("
                    + values
                    + ") WHERE id = ?",
                link));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalidated_reason = 'SETTLED'",
        "invalidated_at = NULL, invalidated_reason = NULL",
        "invalidated_at = invalidated_at + interval '1 second'"
      })
  void invalidatedLinkCannotChangeTimeOrReason(String assignment) {
    UUID link = link();
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'CONNECTED' WHERE id = ?",
        link);
    assertEquals(
        1,
        jdbc.update(
            "UPDATE state.link SET failed_view_count = failed_view_count + 1 WHERE id = ?", link));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE state.link SET " + assignment + " WHERE id = ?", link));
  }

  @Test
  void repairSettlementItemCannotStoreItsOwnPart() {
    UUID room = room();
    UUID repair = repair(room);
    UUID settlement = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.settlement (id, room_record_id, deposit_status, created_at, version) VALUES (?, ?, 'NOT_ENTERED', CURRENT_TIMESTAMP, 0)",
        settlement,
        room);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO state.settlement_item (id, room_record_id, settlement_id, source, repair_case_id, room_part_id, category, status, created_at, version) VALUES (?, ?, ?, 'REPAIR', ?, ?, 'REPAIR', 'PENDING', CURRENT_TIMESTAMP, 0)",
                UUID.randomUUID(),
                room,
                settlement,
                repair,
                part(room)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"'digest', NULL", "NULL, 1"})
  void notificationGroupRequiresBothKeyAndCount(String values) {
    UUID member = member("ACTIVE");
    assertThrows(DataAccessException.class, () -> notification(member, values));
  }

  @Test
  void normalAndGroupedNotificationsAreAccepted() {
    UUID member = member("ACTIVE");
    notification(member, "NULL, NULL");
    notification(member, "'digest', 1");
  }

  private void notification(UUID member, String values) {
    jdbc.update(
        "INSERT INTO state.in_app_notification (id, member_id, kind, group_key, group_count, expires_at, created_at, version) VALUES (?, ?, 'INFO', "
            + values
            + ", CURRENT_TIMESTAMP + interval '30 days', CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        member);
  }

  private long tableCount(String schema) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'",
        Long.class,
        schema);
  }

  private UUID member(String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version) VALUES (?, ?, CASE WHEN ? = 'WITHDRAWN' THEN CURRENT_TIMESTAMP END, CURRENT_TIMESTAMP, 0)",
        id,
        status,
        status);
    return id;
  }

  private UUID acknowledgement(UUID member) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO record.member_acknowledgement (id, member_id, kind, text_version, agreed_at, created_at) VALUES (?, ?, 'TERMS_AGREED', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        id,
        member);
    return id;
  }

  private int purgeAcknowledgements(UUID member) {
    return jdbc.queryForObject(
        "SELECT record.purge_member_acknowledgements(?, CURRENT_TIMESTAMP)", Integer.class, member);
  }

  private UUID cause(UUID room, UUID repair, UUID previous) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO record.cause_note (id, room_record_id, repair_case_id, side, text, basis, previous_id, created_at) VALUES (?, ?, ?, 'TENANT', '설명', 'SEEN', ?, CURRENT_TIMESTAMP)",
        id,
        room,
        repair,
        previous);
    return id;
  }

  private UUID link() {
    UUID room = room();
    UUID party = UUID.randomUUID();
    UUID link = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, status, created_at, version) VALUES (?, ?, 'TENANT', 'EMPTY', CURRENT_TIMESTAMP, 0)",
        party,
        room);
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash, created_at, version) VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        link,
        room,
        party,
        member("ACTIVE"),
        link.toString());
    return link;
  }

  private String appRole() {
    String app = "test_schema_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE ROLE " + app + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
    jdbc.execute("GRANT USAGE ON SCHEMA record, state, log TO " + app);
    jdbc.execute("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA record TO " + app);
    jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA state TO " + app);
    jdbc.execute("GRANT INSERT ON ALL TABLES IN SCHEMA log TO " + app);
    return app;
  }
}
