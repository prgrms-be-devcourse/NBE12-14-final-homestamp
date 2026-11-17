package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataIntegrityViolationException;

/** 같은 건 안에서만 이어지는 참조는 같은 호실이어도 다른 건을 가리키지 못한다. */
class SameCaseReferenceSchemaTest extends SchemaTestSupport {
  @Test
  void contentPreviousMustBeSameCase() {
    UUID room = room();
    UUID repair = repair(room);
    UUID own = currentContent(repair);
    UUID other = currentContent(repair(room));
    assertDoesNotThrow(() -> content(room, repair, 2, own));
    assertRejected("fk_repair_content_previous_id_room", () -> content(room, repair, 3, other));
  }

  @Test
  void causePreviousMustBeSameCase() {
    UUID room = room();
    UUID repair = repair(room);
    UUID own = cause(room, repair, null);
    UUID other = cause(room, repair(room), null);
    assertDoesNotThrow(() -> cause(room, repair, own));
    assertRejected("fk_cause_note_previous_id_room", () -> cause(room, repair, other));
  }

  @Test
  void counterAndReplacedProposalMustBeSameMatter() {
    UUID room = room();
    UUID repair = repair(room);
    UUID own = proposal(room, repair, null, null);
    UUID other = proposal(room, repair(room), null, null);
    assertDoesNotThrow(() -> proposal(room, repair, own, null));
    assertDoesNotThrow(() -> proposal(room, repair, null, own));
    assertRejected("fk_proposal_counter_of_repair_case", () -> proposal(room, repair, other, null));
    assertRejected("fk_proposal_replaces_repair_case", () -> proposal(room, repair, null, other));
  }

  @Test
  void repairCaseProposalPointersMustBeItsOwnProposals() {
    UUID room = room();
    UUID repair = repair(room);
    UUID own = proposal(room, repair, null, null);
    UUID other = proposal(room, repair(room), null, null);
    assertDoesNotThrow(() ->
        jdbc.update(
            "UPDATE repair_case SET current_proposal_id = ?, agreed_proposal_id = ? WHERE id = ?",
            own,
            own,
            repair));
    assertRejected(
        "fk_repair_case_current_proposal",
        () ->
            jdbc.update(
                "UPDATE repair_case SET current_proposal_id = ? WHERE id = ?", other, repair));
    assertRejected(
        "fk_repair_case_agreed_proposal",
        () ->
            jdbc.update(
                "UPDATE repair_case SET agreed_proposal_id = ? WHERE id = ?", other, repair));
  }

  @Test
  void progressAnswerMustAnswerRequestOfSameCase() {
    UUID room = room();
    UUID repair = repair(room);
    UUID own = request(room, "PROGRESS_CHECK", repair, "WAITING", "LANDLORD");
    UUID other = request(room, "PROGRESS_CHECK", repair(room), "WAITING", "LANDLORD");
    assertDoesNotThrow(() -> progress(room, repair, own));
    assertRejected("fk_progress_note_answers_request_id_room", () -> progress(room, repair, other));
  }

  @Test
  void retriedCorrectionMustBeSameTargetAndField() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherRepair = repair(room);
    UUID own = correction(room, repair, "title", null);
    UUID otherTarget = correction(room, otherRepair, "title", null);
    UUID otherField = correction(room, repair, "description", null);
    assertDoesNotThrow(() -> correction(room, repair, "title", own));
    assertRejected(
        "fk_correction_request_retry_of_id_room",
        () -> correction(room, repair, "title", otherTarget));
    assertRejected(
        "fk_correction_request_retry_of_id_room",
        () -> correction(room, repair, "title", otherField));
  }

  // 거절된 문장이 테스트 트랜잭션을 깨지 않도록 저장점으로 되돌린다.
  private void assertRejected(String constraint, Executable executable) {
    jdbc.execute("SAVEPOINT rejected");
    var error = assertThrows(DataIntegrityViolationException.class, executable);
    jdbc.execute("ROLLBACK TO SAVEPOINT rejected");
    assertTrue(error.getMessage().contains(constraint), error::getMessage);
  }

  private UUID currentContent(UUID repair) {
    return jdbc.queryForObject(
        "SELECT current_content_id FROM repair_case WHERE id = ?", UUID.class, repair);
  }

  private UUID content(UUID room, UUID repair, int no, UUID previous) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO repair_content (id, room_record_id, repair_case_id, no, room_part_id, title,
          previous_id, created_by_side, created_at)
        VALUES (?, ?, ?, ?, ?, '고친 내용', ?, 'TENANT', CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        no,
        part(room),
        previous);
    return id;
  }

  private UUID cause(UUID room, UUID repair, UUID previous) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO cause_note (id, room_record_id, repair_case_id, side, text, basis,
          previous_id, created_at)
        VALUES (?, ?, ?, 'TENANT', '설명', 'SEEN', ?, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        previous);
    return id;
  }

  private UUID proposal(UUID room, UUID repair, UUID counterOf, UUID replaces) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO proposal (id, room_record_id, repair_case_id, proposed_by_side,
          counter_of_id, replaces_id, round, created_at)
        VALUES (?, ?, ?, 'TENANT', ?, ?, 1, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        counterOf,
        replaces);
    return id;
  }

  private UUID progress(UUID room, UUID repair, UUID answers) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO progress_note (id, room_record_id, repair_case_id, side, text,
          answers_request_id, created_at)
        VALUES (?, ?, ?, 'TENANT', '업체 연락 기다리는 중', ?, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        answers);
    return id;
  }

  private UUID correction(UUID room, UUID repair, String field, UUID retryOf) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO correction_request (id, room_record_id, field, new_value, repair_case_id,
          target_key, retry_of_id, requested_by_side, status, created_at, version)
        VALUES (?, ?, ?, '"새 값"'::jsonb, ?, ?, ?, 'TENANT', 'REJECTED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        field,
        repair,
        "REPAIR_CASE:" + repair,
        retryOf);
    return id;
  }
}
