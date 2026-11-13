package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class ResponseRequestSchemaTest extends SchemaTestSupport {
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
