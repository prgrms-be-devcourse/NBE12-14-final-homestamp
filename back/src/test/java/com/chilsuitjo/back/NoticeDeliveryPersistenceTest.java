package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 통지의 전달 결과 전이가 V1 {@code state.notice}에 그대로 저장되는지 본다(#120 2단계). 엔티티 메서드로 바꾸면 JPA 변경 감지가 flush 때
 * UPDATE를 보내고, V1 CHECK(상태 값)를 지킨다.
 */
class NoticeDeliveryPersistenceTest extends SchemaTestSupport {

  private static final Instant CREATED = Instant.parse("2026-11-23T01:00:00Z");
  private static final Instant SENT_AT = Instant.parse("2026-11-23T01:00:10Z");
  private static final Instant LATER = Instant.parse("2026-11-23T01:05:00Z");
  private static final Instant DUE = SENT_AT.plus(Duration.ofDays(3));

  @Autowired private NoticeRepository notices;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 전달됨으로_바꾸면_상태와_시각이_저장되고_다음_조회_시각이_비워진다() {
    UUID id = sentNotice();

    notices.findById(id).orElseThrow().markDelivered(LATER);
    entityManager.flush();

    assertThat(row(id))
        .containsEntry("status", "DELIVERED")
        .containsEntry("next_poll_at", null)
        .containsEntry("failed_at", null);
    assertThat(instant(row(id).get("delivered_at"))).isEqualTo(LATER);
    assertThat(instant(row(id).get("sent_at"))).isEqualTo(SENT_AT);
  }

  @Test
  void 전달_실패로_바꾸면_상태와_시각이_저장되고_다음_조회_시각이_비워진다() {
    UUID id = sentNotice();

    notices.findById(id).orElseThrow().markDeliveryFailed(LATER);
    entityManager.flush();

    assertThat(row(id))
        .containsEntry("status", "FAILED")
        .containsEntry("next_poll_at", null)
        .containsEntry("delivered_at", null);
    assertThat(instant(row(id).get("failed_at"))).isEqualTo(LATER);
  }

  @Test
  void 보낼_때_정한_전달_확인_기한이_저장되고_다시_읽어도_결과를_적어도_그대로다() {
    UUID id = sentNotice();

    assertThat(instant(row(id).get("delivery_check_due_at"))).isEqualTo(DUE);
    Notice reloaded = notices.findById(id).orElseThrow();
    assertThat(reloaded.getDeliveryCheckDueAt()).isEqualTo(DUE);

    reloaded.markDelivered(LATER);
    entityManager.flush();
    entityManager.clear();

    assertThat(instant(row(id).get("delivery_check_due_at"))).isEqualTo(DUE);
    assertThat(notices.findById(id).orElseThrow().getDeliveryCheckDueAt()).isEqualTo(DUE);
  }

  /** 보낸 상태의 통지를 저장하고 1차 캐시를 비워, 다음 조회가 DB에서 읽게 한다. */
  private UUID sentNotice() {
    UUID room = room();
    UUID party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        room);
    Notice notice =
        new Notice(
            room, null, null, party, NoticeKind.REQUEST, NoticeChannel.SMS, CREATED, CREATED);
    notices.saveAndFlush(notice);
    notice.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), DUE);
    entityManager.flush();
    entityManager.clear();
    return notice.getId();
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM state.notice WHERE id = ?", id);
  }

  /** JDBC는 {@code timestamptz}를 {@link Timestamp}로 돌려준다. 시각(epoch) 그대로 바꾼다. */
  private static Instant instant(Object value) {
    return ((Timestamp) value).toInstant();
  }
}
