package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DeletionLogSchemaTest extends SchemaTestSupport {
  @Test
  void recordsOptOutDeletionWithoutInventingTargetUuid() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO deletion_log (id, target_kind, phone_hmac, reason, created_at)
        VALUES (?, 'SMS_OPT_OUT', ?, 'PHONE_VERIFIED', CURRENT_TIMESTAMP)
        """,
        id,
        "a".repeat(64));
    assertNull(
        jdbc.queryForObject("SELECT target_id FROM deletion_log WHERE id = ?", UUID.class, id));
  }

  @Test
  void rejectsDeletionWithoutAnyTargetIdentifier() {
    var error =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    """
                    INSERT INTO deletion_log (id, target_kind, reason, created_at)
                    VALUES (?, 'SMS_OPT_OUT', 'PHONE_VERIFIED', CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID()));
    assertTrue(error.getMessage().contains("ck_deletion_log_target"));
  }

  @Test
  void actionDeletionStillRequiresTargetUuidEvenWithPhoneHmac() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                """
                INSERT INTO deletion_log (id, target_kind, phone_hmac, reason, chain_room_id,
                  room_seq, prev_hash, action_hash, created_at)
                VALUES (?, 'ACTION', ?, 'WITHDRAWAL', ?, 1, ?, ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                "a".repeat(64),
                UUID.randomUUID(),
                new byte[32],
                new byte[32]));
  }

  @Test
  void preservesLinkAcrossConsecutiveDeletedActionsWithoutRoomForeignKey() {
    UUID deletedRoom = UUID.randomUUID();
    byte[] hash41 = new byte[32];
    hash41[0] = 41;
    log(deletedRoom, 41, new byte[32], hash41);
    log(deletedRoom, 42, hash41, new byte[32]);
    assertArrayEquals(
        hash41,
        jdbc.queryForObject(
            "SELECT prev_hash FROM deletion_log WHERE chain_room_id = ? AND room_seq = 42",
            byte[].class,
            deletedRoom));
  }

  @Test
  void rejectsTwoExplanationsForSameMissingPosition() {
    UUID room = UUID.randomUUID();
    log(room, 41, new byte[32], new byte[32]);
    var error =
        assertThrows(
            DataIntegrityViolationException.class, () -> log(room, 41, new byte[32], new byte[32]));
    assertTrue(error.getMessage().contains("ux_deletion_log_chain"));
  }

  @Test
  void actionDeletionRequiresPreviousHash() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> log(UUID.randomUUID(), 41, null, new byte[32]));
  }

  @Test
  void previousHashMustBeSha256Length() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> log(UUID.randomUUID(), 41, new byte[31], new byte[32]));
  }

  private void log(UUID room, long seq, byte[] prev, byte[] hash) {
    jdbc.update(
        """
        INSERT INTO deletion_log (id, target_kind, target_id, reason, chain_room_id,
          room_seq, prev_hash, action_hash, created_at)
        VALUES (?, 'ACTION', ?, 'WITHDRAWAL', ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        room,
        seq,
        prev,
        hash);
  }
}
