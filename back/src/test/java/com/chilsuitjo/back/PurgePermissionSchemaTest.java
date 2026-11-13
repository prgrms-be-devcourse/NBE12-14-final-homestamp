package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class PurgePermissionSchemaTest extends SchemaTestSupport {
  @Test
  void appCannotEnableDeletionJustBySettingFlagEvenWithDeleteGrant() {
    UUID room = room();
    UUID repair = repair(room);
    String role = role();
    jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
    jdbc.execute("GRANT SELECT, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
    jdbc.execute("SET LOCAL ROLE " + role);
    jdbc.execute("SET LOCAL app.purge = 'on'");
    var error =
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("DELETE FROM repair_content WHERE repair_case_id = ?", repair));
    assertTrue(error.getMessage().contains("append-only"));
  }

  @Test
  void functionIsNotExecutableByAppByDefault() {
    UUID room = room();
    String role = role();
    jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
    jdbc.execute("SET LOCAL ROLE " + role);
    var error = assertThrows(DataAccessException.class, () -> purge(room));
    assertTrue(
        error
            .getMostSpecificCause()
            .getMessage()
            .contains("permission denied for function purge_room_record"),
        error.getMostSpecificCause()::getMessage);
  }

  @Test
  void dedicatedWorkerCanPurgeWithoutTableDeleteGrantAndAuditSurvives() {
    UUID room = room();
    repair(room);
    UUID action = action(room);
    audit(action, new byte[32]);
    String worker = role();
    jdbc.execute("GRANT USAGE ON SCHEMA public TO " + worker);
    jdbc.execute("GRANT EXECUTE ON FUNCTION purge_room_record(uuid) TO " + worker);
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_table_privilege(?, 'action', 'DELETE')", Boolean.class, worker));
    jdbc.execute("SET LOCAL app.purge = 'off'");
    jdbc.execute("SET LOCAL ROLE " + worker);
    assertTrue(purge(room));
    assertFalse(purge(room));
    assertEquals("off", jdbc.queryForObject("SELECT current_setting('app.purge')", String.class));
    jdbc.execute("RESET ROLE");
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM repair_case WHERE room_record_id = ?", Long.class, room));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM deletion_log WHERE target_id = ?", Long.class, action));
  }

  @Test
  void cannotPurgeBeforePreservingActionAudit() {
    UUID room = room();
    action(room);
    var error = assertThrows(DataAccessException.class, () -> purge(room));
    assertTrue(error.getMessage().contains("action deletion audit is missing or mismatched"));
  }

  @Test
  void cannotPurgeWithMismatchedAuditHash() {
    UUID room = room();
    UUID action = action(room);
    byte[] wrong = new byte[32];
    wrong[0] = 1;
    audit(action, wrong);
    assertThrows(DataAccessException.class, () -> purge(room));
  }

  private String role() {
    String role = "test_purge_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
    return role;
  }

  private boolean purge(UUID room) {
    return jdbc.queryForObject("SELECT purge_room_record(?)", Boolean.class, room);
  }

  private UUID action(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
          type, idempotency_key, created_at)
        VALUES (?, ?, 1, ?, ?, 'server', 'CREATE_ROOM', ?, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        new byte[32],
        new byte[32],
        id.toString());
    return id;
  }

  private void audit(UUID action, byte[] hash) {
    jdbc.update(
        """
        INSERT INTO deletion_log (id, target_kind, target_id, reason, chain_room_id,
          room_seq, prev_hash, action_hash, created_at)
        SELECT ?, 'ACTION', id, 'RETENTION', room_record_id, room_seq, prev_hash, ?, CURRENT_TIMESTAMP
        FROM action WHERE id = ?
        """,
        UUID.randomUUID(),
        hash,
        action);
  }
}
