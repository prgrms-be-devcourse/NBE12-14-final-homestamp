package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ActionConstraintSchemaTest extends SchemaTestSupport {
  @Test
  void rejectsDuplicateSequenceWithinRoom() {
    UUID room = room();
    action(room, 1, "server", "first", "CREATE_ROOM");
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> action(room, 1, "server", "second", "CREATE_ROOM"));
    assertTrue(error.getMessage().contains("uq_action_room_seq"));
  }

  @Test
  void rejectsDuplicateIdempotencyKeyForActorAcrossRooms() {
    action(room(), 1, "server", "same-request", "CREATE_ROOM");
    UUID otherRoom = room();
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> action(otherRoom, 1, "server", "same-request", "CREATE_ROOM"));
    assertTrue(error.getMessage().contains("uq_action_idempotency"));
  }

  @Test
  void allowsSameIdempotencyKeyForDifferentActorsAndSequenceForDifferentRooms() {
    action(room(), 1, "actor-one", "same-request", "CREATE_ROOM");
    action(room(), 1, "actor-two", "same-request", "CREATE_ROOM");
  }

  @Test
  void rejectsActionWithoutHashVersion() {
    UUID room = room();
    var missing =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
                      type, idempotency_key, request_id, created_at)
                    VALUES (?, ?, 1, ?, ?, 'server', 'CREATE_ROOM', 'request', gen_random_uuid(),
                      CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    room,
                    new byte[32],
                    new byte[32]));
    assertTrue(missing.getMessage().contains("hash_version"));
  }

  @Test
  void rejectsHashVersionBelowOne() {
    UUID room = room();
    var zero =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
                      type, idempotency_key, request_id, hash_version, created_at)
                    VALUES (?, ?, 1, ?, ?, 'server', 'CREATE_ROOM', 'request', gen_random_uuid(), 0,
                      CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    room,
                    new byte[32],
                    new byte[32]));
    assertTrue(zero.getMessage().contains("ck_action_hash_version"));
  }

  @Test
  void rejectsActionWithoutRequestId() {
    UUID room = room();
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
                      type, idempotency_key, hash_version, created_at)
                    VALUES (?, ?, 1, ?, ?, 'server', 'CREATE_ROOM', 'request', 1, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    room,
                    new byte[32],
                    new byte[32]));
    assertTrue(error.getMessage().contains("request_id"));
  }

  @Test
  void acceptsShowPartAddedByV5() {
    // V5가 ck_action_type을 다시 만들며 SHOW_PART를 더했다(#136). 기존 종류도 그대로 받는다.
    UUID room = room();
    action(room, 1, "server", "show-part", "SHOW_PART");
    action(room, 2, "server", "hide-part", "HIDE_PART");
  }

  @Test
  void rejectsUnknownActionType() {
    UUID room = room();
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> action(room, 1, "server", "request", "UNKNOWN_ACTION"));
    assertTrue(error.getMessage().contains("ck_action_type"));
  }

  @Test
  void rejectsMoreThanOneTarget() {
    UUID room = room();
    UUID repair = repair(room);
    UUID part = part(room);
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
                      type, idempotency_key, repair_case_id, room_part_id, request_id, hash_version, created_at)
                    VALUES (?, ?, 1, ?, ?, 'server', 'REGISTER', 'request', ?, ?, gen_random_uuid(), 1, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    room,
                    new byte[32],
                    new byte[32],
                    repair,
                    part));
    assertTrue(error.getMessage().contains("ck_action_one_target"));
  }

  private void action(UUID room, long sequence, String actor, String key, String type) {
    jdbc.update(
        """
        INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
          type, idempotency_key, request_id, hash_version, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, gen_random_uuid(), 1, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        room,
        sequence,
        new byte[32],
        new byte[32],
        actor,
        type,
        key);
  }
}
