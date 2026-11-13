package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RoomBoundarySchemaTest extends SchemaTestSupport {
  @Test
  void everyForeignKeyBetweenRoomOwnedTablesIncludesRoomBoundary() {
    var missing =
        jdbc.queryForList(
            """
            SELECT c.conrelid::regclass::text AS child, c.conname
            FROM pg_constraint c
            JOIN pg_attribute child_room ON child_room.attrelid = c.conrelid
              AND child_room.attname = 'room_record_id' AND NOT child_room.attisdropped
            JOIN pg_attribute parent_room ON parent_room.attrelid = c.confrelid
              AND parent_room.attname = 'room_record_id' AND NOT parent_room.attisdropped
            WHERE c.contype = 'f'
              AND NOT (child_room.attnum = ANY(c.conkey) AND parent_room.attnum = ANY(c.confkey))
            """);
    assertEquals(0, missing.size(), missing::toString);
  }

  @Test
  void rejectsContentWhoseRepairBelongsToAnotherRoom() {
    UUID room = room();
    UUID otherRepair = repair(room());
    UUID part = part(room);
    var error =
        assertThrows(DataIntegrityViolationException.class, () -> content(room, otherRepair, part));
    assertTrue(error.getMessage().contains("fk_repair_content_repair_case_id_room"));
  }

  @Test
  void rejectsContentWhosePartBelongsToAnotherRoom() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherPart = part(room());
    var error =
        assertThrows(DataIntegrityViolationException.class, () -> content(room, repair, otherPart));
    assertTrue(error.getMessage().contains("fk_repair_content_room_part_id_room"));
  }

  @Test
  void rejectsActionTargetInAnotherRoom() {
    UUID room = room();
    UUID otherRepair = repair(room());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
                  type, idempotency_key, repair_case_id, created_at)
                VALUES (?, ?, 1, ?, ?, 'server', 'REGISTER', 'test', ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                room,
                new byte[32],
                new byte[32],
                otherRepair));
  }

  @Test
  void deferredCurrentContentCannotPointAcrossRooms() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherRepair = repair(room());
    UUID otherContent =
        jdbc.queryForObject(
            "SELECT current_content_id FROM repair_case WHERE id = ?", UUID.class, otherRepair);
    jdbc.update("UPDATE repair_case SET current_content_id = ? WHERE id = ?", otherContent, repair);
    assertThrows(
        DataIntegrityViolationException.class, () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
  }

  @Test
  void notificationCannotBypassRoomBoundaryWithNullRoom() {
    UUID room = room();
    UUID request = request(room, "END_REQUEST", null, "WAITING", "TENANT");
    // CHECK가 먼저 거절하도록 회원 FK도 실제 값으로 채운다.
    UUID member = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        member);
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO in_app_notification (id, member_id, kind, response_request_id,
                      created_at, expires_at, version)
                    VALUES (?, ?, 'RESPONSE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days', 0)
                    """,
                    UUID.randomUUID(),
                    member,
                    request));
    assertTrue(error.getMessage().contains("ck_in_app_request_room"));
  }

  private void content(UUID room, UUID repair, UUID part) {
    jdbc.update(
        """
        INSERT INTO repair_content (id, room_record_id, repair_case_id, no, room_part_id,
          title, created_by_side, created_at)
        VALUES (?, ?, ?, 2, ?, '고친 내용', 'TENANT', CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        room,
        repair,
        part);
  }
}
