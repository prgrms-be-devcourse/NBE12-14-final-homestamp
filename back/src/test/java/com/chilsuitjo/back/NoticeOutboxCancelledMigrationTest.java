package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** V9: 통지·문자 줄 상태에 CANCELLED를 더한다(#100 U2). 실제 PostgreSQL에서 확인한다. */
class NoticeOutboxCancelledMigrationTest extends SchemaTestSupport {

  private static final List<String> NOTICE_BEFORE =
      List.of("REQUESTED", "SENT", "DELIVERED", "VIEWED", "FAILED");
  private static final List<String> OUTBOX_BEFORE = List.of("PENDING", "SENDING", "DONE", "FAILED");

  @Autowired private NoticeRepository notices;
  @Autowired private OutboxRepository outbox;
  @Autowired private EntityManager entityManager;

  @Test
  void 기존_상태는_그대로_두고_CANCELLED를_더한다() {
    // 트랜잭션 안에서 V8까지의 두 CHECK를 복원한다. 테스트 뒤 DDL도 롤백된다.
    jdbc.execute("ALTER TABLE state.notice DROP CONSTRAINT notice_status_check");
    jdbc.execute(
        """
        ALTER TABLE state.notice ADD CONSTRAINT notice_status_check
        CHECK (status IN ('REQUESTED', 'SENT', 'DELIVERED', 'VIEWED', 'FAILED'))
        """);
    jdbc.execute("ALTER TABLE state.outbox DROP CONSTRAINT outbox_status_check");
    jdbc.execute(
        """
        ALTER TABLE state.outbox ADD CONSTRAINT outbox_status_check
        CHECK (status IN ('PENDING', 'SENDING', 'DONE', 'FAILED'))
        """);
    UUID room = room();
    UUID party = party(room);
    List<UUID> noticeRows = NOTICE_BEFORE.stream().map(s -> insertNotice(room, party, s)).toList();
    List<UUID> outboxRows = OUTBOX_BEFORE.stream().map(this::insertOutbox).toList();
    // 실제 마이그레이션 전에는 기존 행의 FK 검사가 커밋 때 끝나 있다.
    jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");

    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              ScriptUtils.executeSqlScript(
                  connection,
                  new ClassPathResource("db/migration/V9__add_notice_outbox_cancelled.sql"));
              return null;
            });

    for (int i = 0; i < noticeRows.size(); i++) {
      assertThat(status("notice", noticeRows.get(i))).isEqualTo(NOTICE_BEFORE.get(i));
    }
    for (int i = 0; i < outboxRows.size(); i++) {
      assertThat(status("outbox", outboxRows.get(i))).isEqualTo(OUTBOX_BEFORE.get(i));
    }
    assertThat(status("notice", insertNotice(room, party, "CANCELLED"))).isEqualTo("CANCELLED");
    assertThat(status("outbox", insertOutbox("CANCELLED"))).isEqualTo("CANCELLED");
    jdbc.execute("SET CONSTRAINTS ALL DEFERRED");
  }

  @Test
  void 바뀐_통지_제약은_알_수_없는_상태를_거절한다() {
    UUID room = room();
    UUID party = party(room);

    // 실패한 문장 뒤에는 같은 트랜잭션의 문장이 모두 거절되므로 테스트마다 하나만 본다.
    var error =
        assertThrows(
            DataIntegrityViolationException.class, () -> insertNotice(room, party, "CANCELED"));
    assertThat(error.getMessage()).contains("notice_status_check");
  }

  @Test
  void 바뀐_문자_줄_제약은_알_수_없는_상태를_거절한다() {
    var error = assertThrows(DataIntegrityViolationException.class, () -> insertOutbox("SENT"));
    assertThat(error.getMessage()).contains("outbox_status_check");
  }

  @Test
  void 두_테이블의_CHECK는_다시_만들어도_늘지_않는다() {
    assertThat(
            jdbc.queryForList(
                """
                SELECT conname FROM pg_constraint
                WHERE contype = 'c' AND conrelid IN ('state.notice'::regclass, 'state.outbox'::regclass)
                """,
                String.class))
        .containsExactlyInAnyOrder(
            "notice_channel_check",
            "notice_kind_check",
            "notice_status_check",
            "outbox_attempts_check",
            "outbox_kind_check",
            "outbox_status_check");
  }

  @Test
  void 엔티티로_취소한_상태가_DB에_문자열로_저장된다() {
    UUID room = room();
    Instant now = Instant.parse("2026-11-26T01:00:00Z");
    Notice notice =
        notices.save(
            new Notice(
                room, null, null, party(room), NoticeKind.INVITE, NoticeChannel.SMS, now, now));
    Outbox row = outbox.save(Outbox.sms(notice.getId(), now, now));

    assertThat(row.markCancelled()).isTrue();
    assertThat(notice.markCancelled()).isTrue();
    entityManager.flush();

    assertThat(status("notice", notice.getId())).isEqualTo("CANCELLED");
    assertThat(status("outbox", row.getId())).isEqualTo("CANCELLED");
  }

  private UUID party(UUID room) {
    UUID party = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)",
        party,
        room);
    return party;
  }

  private UUID insertNotice(UUID room, UUID party, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.notice
          (id, room_record_id, recipient_party_id, kind, channel, status, created_at, version)
        VALUES (?, ?, ?, 'INVITE', 'SMS', ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        party,
        status);
    return id;
  }

  private UUID insertOutbox(String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.outbox
          (id, kind, dedupe_key, payload, status, attempts, next_attempt_at, created_at, version)
        VALUES (?, 'SMS', ?, CAST(? AS jsonb), ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        id,
        "sms:" + id,
        "{\"noticeId\":\"" + id + "\"}",
        status);
    return id;
  }

  private String status(String table, UUID id) {
    return jdbc.queryForObject(
        "SELECT status FROM state." + table + " WHERE id = ?", String.class, id);
  }
}
