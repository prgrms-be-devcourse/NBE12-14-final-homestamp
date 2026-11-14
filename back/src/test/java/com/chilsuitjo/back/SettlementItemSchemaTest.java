package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SettlementItemSchemaTest extends SchemaTestSupport {
  @Test
  void rejectsTwoActiveCandidatesFromSameRepair() {
    UUID room = room();
    UUID settlement = settlement(room);
    UUID repair = repair(room);
    item(room, settlement, repair, "PENDING");
    var error =
        assertThrows(
            DataIntegrityViolationException.class, () -> item(room, settlement, repair, "AGREED"));
    assertTrue(error.getMessage().contains("ux_settlement_item_active_repair"));
  }

  @Test
  void allowsReplacementAfterExclusionAndMultipleManualItems() {
    UUID room = room();
    UUID settlement = settlement(room);
    UUID repair = repair(room);
    UUID old = item(room, settlement, repair, "PENDING");
    jdbc.update("UPDATE settlement_item SET status = 'EXCLUDED' WHERE id = ?", old);
    item(room, settlement, repair, "PENDING");
    item(room, settlement, null, "PENDING");
    item(room, settlement, null, "PENDING");
  }

  private UUID settlement(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO settlement (id, room_record_id, opened_at, deposit_status, created_at, version)
        VALUES (?, ?, CURRENT_TIMESTAMP, 'NOT_ENTERED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }

  private UUID item(UUID room, UUID settlement, UUID repair, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO settlement_item (id, room_record_id, settlement_id, source, repair_case_id,
          category, status, created_at, version)
        VALUES (?, ?, ?, ?, ?, 'REPAIR', ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        settlement,
        repair == null ? "MANUAL" : "REPAIR",
        repair,
        status);
    return id;
  }
}
