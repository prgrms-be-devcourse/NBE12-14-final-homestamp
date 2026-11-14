package com.chilsuitjo.back;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Flyway가 만든 실제 PostgreSQL에서 내용 지문의 저장 제약을 검사한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ActionContentDigestSchemaTest {

  @Autowired private JdbcTemplate jdbc;

  private UUID roomId;

  @BeforeEach
  void createRoom() {
    roomId = UUID.randomUUID();
    jdbc.update("INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')");
    jdbc.update(
        """
        INSERT INTO room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        roomId);
  }

  @Test
  void storesSha256Digest() {
    byte[] digest = new byte[32];
    digest[0] = 1;
    UUID actionId = insertAction(digest);

    assertArrayEquals(
        digest,
        jdbc.queryForObject(
            "SELECT content_digest FROM action WHERE id = ?", byte[].class, actionId));
  }

  @Test
  void allowsActionWithoutContent() {
    UUID actionId = insertAction(null);

    assertNull(
        jdbc.queryForObject(
            "SELECT content_digest FROM action WHERE id = ?", byte[].class, actionId));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 31, 33, 64})
  void rejectsDigestWithWrongLength(int length) {
    var exception =
        assertThrows(DataIntegrityViolationException.class, () -> insertAction(new byte[length]));

    assertTrue(exception.getMessage().contains("ck_action_content_digest_size"));
  }

  @Test
  void rejectsDigestReplacement() {
    UUID actionId = insertAction(new byte[32]);
    byte[] replacement = new byte[32];
    replacement[0] = 1;

    var exception =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.update(
                    "UPDATE action SET content_digest = ? WHERE id = ?", replacement, actionId));

    assertTrue(exception.getMessage().contains("append-only"));
  }

  private UUID insertAction(byte[] digest) {
    UUID actionId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO action
          (id, room_record_id, room_seq, prev_hash, hash, content_digest,
           actor_key, type, idempotency_key, created_at)
        VALUES (?, ?, 1, ?, ?, ?, 'server', 'CREATE_ROOM', ?, CURRENT_TIMESTAMP)
        """,
        actionId,
        roomId,
        new byte[32],
        new byte[32],
        digest,
        actionId.toString());
    return actionId;
  }
}
