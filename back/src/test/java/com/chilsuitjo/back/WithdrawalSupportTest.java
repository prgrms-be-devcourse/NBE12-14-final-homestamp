package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.JpaWaitService;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.notification.service.SmsCancellation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴(KEEP-4, #424)가 쓸 D 공개 계약을 실제 PostgreSQL에서 확인한다: 업무 시각을 받는 {@code closeAll}, 발급자 기준 링크 무효
 * {@code invalidateIssuedBy}, 탈퇴용 문자 취소. 탈퇴 API는 아직 없으므로 테스트 트랜잭션이 탈퇴 트랜잭션을 흉내 낸다(호실 잠금 → 세 호출, 같은
 * 시각). 실제 탈퇴 API 전체를 확인한 것은 아니다.
 *
 * <p>{@link SmsCancellationServiceTest}와 같은 설정이라 같은 컨텍스트(작업자 예약 실행 꺼짐)를 쓰고, 끝나면 남은 보낼 줄을 닫는다.
 */
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.RecipientsConfig.class})
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
class WithdrawalSupportTest {

  /** 탈퇴 업무 시각. 나노초를 붙여 마이크로초로 맞추는지도 본다. */
  private static final Instant WITHDRAWN = Instant.parse("2026-11-28T05:00:00.123456789Z");

  private static final Instant STORED = Instant.parse("2026-11-28T05:00:00.123456Z");
  private static final Instant EARLIER = Instant.parse("2026-11-20T01:00:00Z");

  // 테스트 컨텍스트의 WaitService는 가짜가 @Primary라 실제 구현을 직접 받는다
  @Autowired private JpaWaitService waits;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private SmsCancellation cancellation;
  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private RoomGateReader roomGates;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> rooms = new ArrayList<>();
  private UUID leaver;
  private UUID stayer;
  private UUID room;
  private UUID tenantSeat;
  private UUID landlordSeat;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999992', '테스트', '탈퇴동')"
            + " ON CONFLICT DO NOTHING");
    leaver = member();
    stayer = member();
    room = room();
    tenantSeat = party(room, "TENANT");
    landlordSeat = party(room, "LANDLORD");
  }

  @AfterEach
  void closeLeftovers() {
    for (UUID r : rooms) {
      jdbc.update(
          """
          UPDATE outbox SET status = 'DONE'
          WHERE status IN ('PENDING', 'SENDING')
            AND dedupe_key IN (SELECT 'sms:' || id FROM notice WHERE room_record_id = ?)
          """,
          r);
    }
  }

  @Test
  void 탈퇴_트랜잭션_하나에서_세_호출이_같은_시각으로_요청을_닫고_링크를_무효로_하고_보내지_않은_문자만_취소한다() {
    // 요청: 양방향 열린 요청은 닫히고, 이미 답했거나 닫힌 요청은 그대로(03 L)
    UUID waitingFromTenant = request("CORRECTION_CONFIRM", "TENANT", "WAITING");
    UUID unconfirmedFromLandlord = request("PROGRESS_CHECK", "LANDLORD", "UNCONFIRMED");
    UUID answered = request("DEFECT_CONFIRM", "TENANT", "ANSWERED");
    UUID closedBefore = closedRequest();
    // 링크: 탈퇴자가 발급한 유효 초대·정산 초대는 무효, 이미 무효인 것은 처음 이유·시각 그대로, 다른 회원·다른 호실 링크는 그대로
    UUID invite = link(room, landlordSeat, leaver, "INVITE", null);
    UUID settlementInvite = link(room, landlordSeat, leaver, "SETTLEMENT_INVITE", null);
    UUID alreadyInvalid = link(room, landlordSeat, leaver, "INVITE", "REINVITED");
    UUID othersLink = link(room, tenantSeat, stayer, "INVITE", null);
    UUID otherRoom = room();
    UUID otherRoomLink = link(otherRoom, party(otherRoom, "LANDLORD"), leaver, "INVITE", null);
    // 문자: 보내기 전(PENDING)만 취소. 작업자가 가져간 줄·보낸 문자·다른 회원 링크의 문자는 그대로
    UUID pending = enqueue(landlordSeat, invite);
    UUID pendingOnOldLink = enqueue(landlordSeat, alreadyInvalid);
    UUID sending = enqueue(landlordSeat, settlementInvite);
    setOutbox(sending, "SENDING");
    UUID sent = enqueue(landlordSeat, invite);
    setOutbox(sent, "DONE");
    jdbc.update(
        "UPDATE notice SET status = 'SENT', sent_at = ?, delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(EARLIER),
        Timestamp.from(EARLIER.plusSeconds(86_400)),
        sent);
    UUID othersPending = enqueue(tenantSeat, othersLink);

    Result result =
        transaction.execute(status -> {
          roomGates.lockAndRead(room, Actor.server()); // 탈퇴(A)가 먼저 잡는 호실 잠금
          int closed =
              waits.closeAll(
                  room,
                  ClosedReason.COUNTERPART_WITHDRAWN,
                  EnumSet.allOf(RequestKind.class),
                  WITHDRAWN);
          List<UUID> links =
              inviteLinks.invalidateIssuedBy(
                  room, leaver, LinkInvalidReason.ISSUER_WITHDRAWN, WITHDRAWN);
          int cancelled = cancellation.cancelPendingSmsOnWithdrawal(room, links);
          return new Result(closed, links, cancelled);
        });

    assertThat(result.closed()).isEqualTo(2);
    assertThat(requestRow(waitingFromTenant)).isEqualTo("CLOSED COUNTERPART_WITHDRAWN " + STORED);
    assertThat(requestRow(unconfirmedFromLandlord))
        .isEqualTo("CLOSED COUNTERPART_WITHDRAWN " + STORED);
    assertThat(requestRow(answered)).isEqualTo("ANSWERED null null");
    assertThat(requestRow(closedBefore)).isEqualTo("CLOSED CLOSED_BY_BOTH " + EARLIER);

    assertThat(result.links()).containsExactlyInAnyOrder(invite, settlementInvite, alreadyInvalid);
    assertThat(linkRow(invite)).isEqualTo("ISSUER_WITHDRAWN " + STORED);
    assertThat(linkRow(settlementInvite)).isEqualTo("ISSUER_WITHDRAWN " + STORED);
    assertThat(linkRow(alreadyInvalid)).isEqualTo("REINVITED " + EARLIER);
    assertThat(linkRow(othersLink)).isEqualTo("null null");
    assertThat(linkRow(otherRoomLink)).isEqualTo("null null");

    assertThat(result.cancelled()).isEqualTo(2);
    assertThat(sms(pending)).isEqualTo("CANCELLED CANCELLED");
    assertThat(sms(pendingOnOldLink)).isEqualTo("CANCELLED CANCELLED");
    assertThat(sms(sending)).isEqualTo("REQUESTED SENDING");
    assertThat(sms(sent)).isEqualTo("SENT DONE");
    assertThat(sms(othersPending)).isEqualTo("REQUESTED PENDING");
  }

  @Test
  void 셋_중_하나가_실패하면_요청_닫기와_링크_무효까지_모두_되돌아간다() {
    UUID waiting = request("CORRECTION_CONFIRM", "TENANT", "WAITING");
    UUID invite = link(room, landlordSeat, leaver, "INVITE", null);
    UUID broken = enqueue(landlordSeat, invite);
    // 지금 코드로 생길 수 없는 조합(줄은 PENDING인데 통지는 보낸 뒤). 취소가 실패를 숨기지 않고 던진다
    jdbc.update(
        "UPDATE notice SET status = 'SENT', sent_at = ?, delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(EARLIER),
        Timestamp.from(EARLIER.plusSeconds(86_400)),
        broken);

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              roomGates.lockAndRead(room, Actor.server());
              waits.closeAll(
                  room,
                  ClosedReason.COUNTERPART_WITHDRAWN,
                  EnumSet.allOf(RequestKind.class),
                  WITHDRAWN);
              List<UUID> links =
                  inviteLinks.invalidateIssuedBy(
                      room, leaver, LinkInvalidReason.ISSUER_WITHDRAWN, WITHDRAWN);
              cancellation.cancelPendingSmsOnWithdrawal(room, links);
            }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(requestRow(waiting)).isEqualTo("WAITING null null");
    assertThat(linkRow(invite)).isEqualTo("null null");
    assertThat(sms(broken)).isEqualTo("SENT PENDING");
  }

  @Test
  void 업무_시각을_받는_호출은_pipeline_밖에서_되고_기존_호출은_전처럼_save_밖을_거절한다() {
    UUID waiting = request("CORRECTION_CONFIRM", "TENANT", "WAITING");
    UUID invite = link(room, landlordSeat, leaver, "INVITE", null);

    // 기존 계약: 행동 시각을 읽으므로 save 밖이면 IllegalStateException(그대로)
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(s ->
                waits.closeAll(
                    room, ClosedReason.COUNTERPART_WITHDRAWN, EnumSet.allOf(RequestKind.class))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(
                s -> cancellation.cancelPendingSms(room, List.of(invite))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requestRow(waiting)).isEqualTo("WAITING null null");

    // 같은 호출을 업무 시각으로: 호실 잠금을 스스로 다시 확인하고 같은 결과를 남긴다
    int closed =
        transaction.execute(s ->
            waits.closeAll(
                room,
                ClosedReason.COUNTERPART_WITHDRAWN,
                EnumSet.allOf(RequestKind.class),
                WITHDRAWN));
    assertThat(closed).isEqualTo(1);
    assertThat(requestRow(waiting)).isEqualTo("CLOSED COUNTERPART_WITHDRAWN " + STORED);
  }

  @Test
  void 트랜잭션_밖에서는_세_호출_모두_거절한다() {
    assertThatThrownBy(() ->
            waits.closeAll(
                room,
                ClosedReason.COUNTERPART_WITHDRAWN,
                EnumSet.allOf(RequestKind.class),
                WITHDRAWN))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() ->
            inviteLinks.invalidateIssuedBy(
                room, leaver, LinkInvalidReason.ISSUER_WITHDRAWN, WITHDRAWN))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> cancellation.cancelPendingSmsOnWithdrawal(room, List.of()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void 발급자_기준_무효는_발급한_사람_탈퇴_이유만_받는다() {
    UUID invite = link(room, landlordSeat, leaver, "INVITE", null);

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(s ->
                inviteLinks.invalidateIssuedBy(
                    room, leaver, LinkInvalidReason.RESIDENCE_ENDED, WITHDRAWN)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(linkRow(invite)).isEqualTo("null null");
  }

  @Test
  void 발급한_링크가_없으면_빈_목록이고_취소도_아무것도_바꾸지_않는다() {
    UUID othersLink = link(room, tenantSeat, stayer, "INVITE", null);
    UUID othersPending = enqueue(tenantSeat, othersLink);

    int cancelled =
        transaction.execute(s -> {
          List<UUID> links =
              inviteLinks.invalidateIssuedBy(
                  room, leaver, LinkInvalidReason.ISSUER_WITHDRAWN, WITHDRAWN);
          assertThat(links).isEmpty();
          return cancellation.cancelPendingSmsOnWithdrawal(room, links);
        });

    assertThat(cancelled).isZero();
    assertThat(linkRow(othersLink)).isEqualTo("null null");
    assertThat(sms(othersPending)).isEqualTo("REQUESTED PENDING");
  }

  // ---- 도우미 ----

  private record Result(int closed, List<UUID> links, int cancelled) {}

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999992', '탈퇴 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    rooms.add(id);
    return id;
  }

  private UUID party(UUID roomId, String side) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, ?, 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        side);
    return id;
  }

  private UUID link(UUID roomId, UUID partyId, UUID issuer, String kind, String invalidReason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,
          invalidated_at, invalidated_reason, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        partyId,
        issuer,
        kind,
        id.toString(),
        invalidReason != null ? Timestamp.from(EARLIER) : null,
        invalidReason);
    return id;
  }

  private UUID request(String kind, String side, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        room,
        kind,
        side,
        status,
        Timestamp.from(EARLIER.minusSeconds(60)));
    return id;
  }

  private UUID closedRequest() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.response_request (id, room_record_id, kind, requested_by_side, status,
          closed_reason, closed_at, created_at, version)
        VALUES (?, ?, 'CORRECTION_CONFIRM', 'LANDLORD', 'CLOSED', 'CLOSED_BY_BOTH', ?, ?, 0)
        """,
        id,
        room,
        Timestamp.from(EARLIER),
        Timestamp.from(EARLIER.minusSeconds(60)));
    return id;
  }

  private UUID enqueue(UUID partyId, UUID linkId) {
    return transaction.execute(s ->
        noticeOutbox.enqueueSms(
            new SmsNoticeCommand(room, partyId, NoticeKind.INVITE, null, linkId)));
  }

  private void setOutbox(UUID noticeId, String status) {
    jdbc.update("UPDATE outbox SET status = ? WHERE dedupe_key = ?", status, "sms:" + noticeId);
  }

  private String requestRow(UUID id) {
    return jdbc.queryForObject(
        "SELECT status, closed_reason, closed_at FROM state.response_request WHERE id = ?",
        (rs, n) ->
            rs.getString(1)
                + " "
                + rs.getString(2)
                + " "
                + (rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
        id);
  }

  private String linkRow(UUID id) {
    return jdbc.queryForObject(
        "SELECT invalidated_reason, invalidated_at FROM link WHERE id = ?",
        (rs, n) ->
            rs.getString(1)
                + " "
                + (rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()),
        id);
  }

  private String sms(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT n.status, o.status FROM notice n JOIN outbox o ON o.dedupe_key = 'sms:' || n.id"
            + " WHERE n.id = ?",
        (rs, n) -> rs.getString(1) + " " + rs.getString(2),
        noticeId);
  }
}
