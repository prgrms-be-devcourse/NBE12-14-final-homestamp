package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/** 머리(C)가 가리키는 현재·확인 판은 같은 호실이어도 그 머리의 판이어야 한다. */
class HeadVersionSchemaTest extends SchemaTestSupport {
  @Test
  void acceptsHeadsPointingToTheirOwnVersions() {
    UUID room = room();
    UUID repair = repair(room);
    payment(room, repair);
    jdbc.update(
        "UPDATE repair_case SET confirmed_content_id = current_content_id WHERE id = ?", repair);
    assertDoesNotThrow(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"current_content_id", "confirmed_content_id"})
  void rejectsRepairContentOfAnotherCaseInSameRoom(String column) {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherContent = currentContent(repair(room));
    jdbc.update("UPDATE repair_case SET " + column + " = ? WHERE id = ?", otherContent, repair);
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    assertTrue(error.getMessage().contains("fk_repair_case_" + column.replace("_id", "")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"current_version_id", "confirmed_version_id"})
  void rejectsPaymentVersionOfAnotherPaymentInSameRoom(String column) {
    UUID room = room();
    UUID repair = repair(room);
    UUID payment = payment(room, repair);
    UUID otherVersion = currentVersion(payment(room, repair));
    jdbc.update("UPDATE payment SET " + column + " = ? WHERE id = ?", otherVersion, payment);
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE"));
    assertTrue(error.getMessage().contains("fk_payment_" + column.replace("_id", "")));
  }

  private UUID currentContent(UUID repair) {
    return jdbc.queryForObject(
        "SELECT current_content_id FROM repair_case WHERE id = ?", UUID.class, repair);
  }

  private UUID currentVersion(UUID payment) {
    return jdbc.queryForObject(
        "SELECT current_version_id FROM payment WHERE id = ?", UUID.class, payment);
  }

  private UUID payment(UUID room, UUID repair) {
    UUID id = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO payment (id, room_record_id, repair_case_id, current_version_id,
          confirm_strength, created_at, version)
        VALUES (?, ?, ?, ?, 'ONE_SIDE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        repair,
        version);
    jdbc.update(
        """
        INSERT INTO payment_version (id, room_record_id, payment_id, no, payer_kind, payee_kind,
          amount, paid_on, recorded_by_side, created_at)
        VALUES (?, ?, ?, 1, 'TENANT', 'VENDOR', 10000, CURRENT_DATE, 'TENANT', CURRENT_TIMESTAMP)
        """,
        version,
        room,
        id);
    return id;
  }
}
