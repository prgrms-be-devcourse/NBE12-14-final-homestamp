package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class RoomCascadeSchemaTest extends SchemaTestSupport {
  @Test
  void deletesOnlyOwnedGraphIncludingCircularVersionAndPhotoReferences() {
    UUID room = graph();
    UUID otherRoom = graph();
    jdbc.execute("SET LOCAL app.purge = 'on'");
    assertEquals(1, jdbc.update("DELETE FROM room_record WHERE id = ?", room));
    for (String table :
        jdbc.queryForList(
            """
            SELECT table_name FROM information_schema.columns
            WHERE table_schema = 'public' AND column_name = 'room_record_id'
            """,
            String.class)) {
      assertEquals(
          0L,
          jdbc.queryForObject(
              "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room),
          table);
    }
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM room_record WHERE id = ?", Long.class, otherRoom));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM repair_case WHERE room_record_id = ?", Long.class, otherRoom));
  }

  @Test
  void ordinaryDeleteStillCannotEraseAppendOnlyChildren() {
    UUID room = graph();
    assertThrows(
        DataAccessException.class, () -> jdbc.update("DELETE FROM room_record WHERE id = ?", room));
  }

  @Test
  void roomMergeReferenceCannotCascadeIntoAnotherRoom() {
    UUID target = graph();
    UUID source = room();
    jdbc.update(
        "UPDATE room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        target,
        source);
    jdbc.execute("SET LOCAL app.purge = 'on'");
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM room_record WHERE id = ?", target));
  }

  private UUID graph() {
    UUID room = room();
    UUID repair = repair(room);
    UUID request = request(room, "DEFECT_CONFIRM", repair, "WAITING", "TENANT");
    UUID photo = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO photo (id, room_record_id, repair_case_id, stage, uploaded_by_side,
          original_key, thumb_key, original_sha256, thumb_sha256, size_bytes, created_at)
        VALUES (?, ?, ?, 'BEFORE', 'TENANT', ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
        """,
        photo,
        room,
        repair,
        photo.toString(),
        photo + "-thumb",
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        INSERT INTO photo_visibility (photo_id, room_record_id, created_at, version)
        VALUES (?, ?, CURRENT_TIMESTAMP, 0)
        """,
        photo,
        room);
    jdbc.update(
        """
        INSERT INTO action (id, room_record_id, room_seq, prev_hash, hash, actor_key,
          type, idempotency_key, response_request_id, created_at)
        VALUES (?, ?, 1, ?, ?, 'server', 'REGISTER', ?, ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        room,
        new byte[32],
        new byte[32],
        room.toString(),
        request);
    return room;
  }
}
