package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SituationCheckSchemaTest extends SchemaTestSupport {
  @Test
  void targetsSpecificNoteAndAllowsAnotherNote() {
    UUID room = room();
    UUID repair = repair(room);
    check(room, note(room, repair));
    check(room, note(room, repair));
  }

  @Test
  void rejectsDuplicateWaitingCheckForSameNote() {
    UUID room = room();
    UUID note = note(room, repair(room));
    check(room, note);
    var error = assertThrows(DataIntegrityViolationException.class, () -> check(room, note));
    assertTrue(error.getMessage().contains("ux_response_request_waiting"));
  }

  @Test
  void rejectsNoteFromAnotherRoom() {
    UUID room = room();
    UUID otherRoom = room();
    UUID note = note(otherRoom, repair(otherRoom));
    var error = assertThrows(DataIntegrityViolationException.class, () -> check(room, note));
    assertTrue(error.getMessage().contains("fk_response_request_progress_note_id_room"));
  }

  @Test
  void situationCheckRequiresNote() {
    UUID room = room();
    assertThrows(DataIntegrityViolationException.class, () -> check(room, null));
  }

  private UUID note(UUID room, UUID repair) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO progress_note (id, room_record_id, repair_case_id, side, text, asks_check, created_at)
        VALUES (?, ?, ?, 'TENANT', '누수가 계속됩니다', true, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair);
    return id;
  }

  private void check(UUID room, UUID note) {
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, progress_note_id,
          requested_by_side, status, created_at, version)
        VALUES (?, ?, 'SITUATION_CHECK', ?, 'TENANT', 'WAITING', CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        note);
  }
}
