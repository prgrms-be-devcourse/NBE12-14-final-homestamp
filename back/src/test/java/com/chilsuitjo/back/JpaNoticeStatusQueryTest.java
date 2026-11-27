package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.NoticeStep;
import com.chilsuitjo.back.notification.dto.SentRequest;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeLine;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.NoticeStatusQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 보낸 측 통지 상태 조회({@link NoticeStatusQuery}, 06 NOTI-4a)가 PostgreSQL의 요청·통지 행을 호실·요청한 측으로만 읽고, 상대에게 간
 * 통지만 이력에 담는지 본다.
 */
class JpaNoticeStatusQueryTest extends SchemaTestSupport {

  private static final Instant T1 = Instant.parse("2026-11-27T01:00:00.123456Z");
  private static final Instant T2 = Instant.parse("2026-11-27T02:00:00Z");
  private static final Instant T3 = Instant.parse("2026-11-27T03:00:00Z");

  @Autowired private NoticeStatusQuery query;

  @Test
  void 보는_측이_요청한_요청만_닫힌_것까지_만든_순서로_준다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID first = request(room, "DEFECT_CONFIRM", repair, "ANSWERED", "TENANT", T1);
    UUID second = request(room, "CLOSE_CONFIRM", repair, "WAITING", "TENANT", T2);
    request(room, "DEFECT_CONFIRM", repair(room), "WAITING", "LANDLORD", T1); // 상대가 요청함
    UUID otherRoom = room();
    request(otherRoom, "DEFECT_CONFIRM", repair(otherRoom), "WAITING", "TENANT", T1);

    List<SentRequest> sent = query.sentBy(room, PartySide.TENANT);

    assertThat(sent)
        .extracting(SentRequest::requestId, s -> s.request().kind(), s -> s.request().status())
        .containsExactly(
            tuple(first, RequestKind.DEFECT_CONFIRM, RequestStatus.ANSWERED),
            tuple(second, RequestKind.CLOSE_CONFIRM, RequestStatus.WAITING));
    assertThat(sent.get(0).request().requestedBySide()).isEqualTo(PartySide.TENANT);
    assertThat(sent.get(0).request().openedAt()).isEqualTo(T1);
  }

  @Test
  void 통지가_없으면_한_줄_없이_빈_이력이다() {
    UUID room = room();
    request(room, "DEFECT_CONFIRM", repair(room), "WAITING", "TENANT", T1);

    SentRequest only = query.sentBy(room, PartySide.TENANT).getFirst();

    assertThat(only.line()).isNull();
    assertThat(only.renotified()).isFalse();
    assertThat(only.history()).isEmpty();
  }

  @Test
  void 이력은_요청_통지와_재통지만_만든_순서로_담고_마지막이_한_줄이다() {
    UUID room = room();
    UUID party = party(room);
    UUID request = request(room, "DEFECT_CONFIRM", repair(room), "WAITING", "TENANT", T1);
    notice(room, party, request, "REQUEST", "DELIVERED", T1);
    notice(room, party, request, "INFO", "DELIVERED", T2); // 요청한 측에게 가는 알려 줌일 수 있음
    notice(room, party, request, "RENOTIFY", "REQUESTED", T3);

    SentRequest only = query.sentBy(room, PartySide.TENANT).getFirst();

    assertThat(only.history())
        .extracting(NoticeStep::kind, NoticeStep::status, NoticeStep::requestedAt)
        .containsExactly(
            tuple(NoticeKind.REQUEST, NoticeStatus.DELIVERED, T1),
            tuple(NoticeKind.RENOTIFY, NoticeStatus.REQUESTED, T3));
    assertThat(only.history().getFirst().deliveredAt()).isEqualTo(T1.plusSeconds(60));
    assertThat(only.line()).isEqualTo(NoticeLine.SOON);
    assertThat(only.renotified()).isTrue();
  }

  @Test
  void 받는_쪽은_상대가_보낸_요청의_통지를_보지_않는다() {
    UUID room = room();
    UUID party = party(room);
    UUID request = request(room, "DEFECT_CONFIRM", repair(room), "WAITING", "TENANT", T1);
    notice(room, party, request, "REQUEST", "SENT", T1);

    assertThat(query.sentBy(room, PartySide.LANDLORD)).isEmpty();
  }

  private UUID request(
      UUID room, String kind, UUID repair, String status, String side, Instant createdAt) {
    UUID id = request(room, kind, repair, status, side);
    jdbc.update(
        "UPDATE response_request SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    return id;
  }

  private UUID party(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }

  /** 전달·열람 시각은 만든 뒤 1분, 보낸 시각은 만든 시각으로 둔다(상태에 맞는 칸만 채움). */
  private void notice(
      UUID room, UUID party, UUID request, String kind, String status, Instant createdAt) {
    boolean sent = !status.equals("REQUESTED");
    boolean delivered = status.equals("DELIVERED") || status.equals("VIEWED");
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, response_request_id, recipient_party_id, kind,
          channel, status, sent_at, delivered_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, 'SMS', ?, ?, ?, ?, 0)
        """,
        UUID.randomUUID(),
        room,
        request,
        party,
        kind,
        status,
        sent ? Timestamp.from(createdAt) : null,
        delivered ? Timestamp.from(createdAt.plusSeconds(60)) : null,
        Timestamp.from(createdAt));
  }
}
