package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class ResponseRequestSchemaTest extends SchemaTestSupport {
  @Test
  void proposalStillTreatsUnconfirmedRequestAsWaiting() {
    UUID room = room();
    UUID repair = repair(room);
    request(room, "PROPOSAL", repair, "UNCONFIRMED", "TENANT");
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> request(room, "PROPOSAL", repair, "WAITING", "LANDLORD"));
    assertTrue(error.getMessage().contains("ux_proposal_waiting"));
  }

  @Test
  void inspectionAllowsBothDirectionsButNotDuplicateDirection() {
    UUID room = room();
    UUID inspection = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inspection (id, room_record_id, kind, author_side, status, created_at, version)
        VALUES (?, ?, 'MOVE_IN', 'TENANT', 'DRAFT', CURRENT_TIMESTAMP, 0)
        """,
        inspection,
        room);
    inspectionRequest(room, inspection, "TENANT");
    inspectionRequest(room, inspection, "LANDLORD");
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> inspectionRequest(room, inspection, "TENANT"));
    assertTrue(error.getMessage().contains("ux_inspection_confirm_waiting"));
  }

  private void inspectionRequest(UUID room, UUID inspection, String side) {
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, inspection_id,
          requested_by_side, status, created_at, version)
        VALUES (?, ?, 'INSPECTION_CONFIRM', ?, ?, 'WAITING', CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        inspection,
        side);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"DEFECT_CONFIRM", "CORRECTION_CONFIRM", "CLOSE_CONFIRM", "PROGRESS_CHECK"})
  void rejectsSecondWaitingRequestForSameTargetEvenFromOtherSide(String kind) {
    UUID room = room();
    UUID repair = repair(room);
    request(room, kind, repair, "WAITING", "TENANT");

    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> request(room, kind, repair, "WAITING", "LANDLORD"));
    assertTrue(error.getMessage().contains("ux_response_request_waiting"));
  }

  @Test
  void rejectsDuplicateRoomRequestWithoutChildTarget() {
    UUID room = room();
    request(room, "END_REQUEST", null, "WAITING", "TENANT");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> request(room, "END_REQUEST", null, "WAITING", "LANDLORD"));
  }

  @Test
  void allowsOtherKindOtherTargetOtherRoomAndAnsweredHistory() {
    UUID room = room();
    UUID repair = repair(room);
    request(room, "DEFECT_CONFIRM", repair, "WAITING", "TENANT");
    request(room, "CLOSE_CONFIRM", repair, "WAITING", "TENANT");
    request(room, "DEFECT_CONFIRM", repair(room), "WAITING", "TENANT");
    UUID other = room();
    request(other, "DEFECT_CONFIRM", repair(other), "WAITING", "TENANT");
    request(room, "DEFECT_CONFIRM", repair, "ANSWERED", "TENANT");
  }
}
