package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.RequestContextTestSupport;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.LinkInvalidReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 초대 거절의 D 지원 계약을 실제 DB로 본다(#421·#392, decisions 2026-11-29). room의 거절 저장({@code PartyStateStore}
 * {@code Rejecting.save})이 아직 부르지 않으므로, 그 저장이 부를 순서(요청 닫기 → 그 자리 링크 무효 → 그 링크의 보내지 않은 문자 취소)를 한
 * 트랜잭션·한 행동 시각에서 직접 부른다. 사용자 흐름 전체(거절 API → 거주 종료 API)는 room이 연결한 뒤 그 PR에서 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class InviteRejectSupportTest {

  private static final Instant AT = Instant.parse("2026-11-29T01:00:00.123456Z");
  private static final Instant REJECTED_AT = Instant.parse("2026-11-29T02:00:00.654321Z");

  @Autowired private JpaWaitService waits;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private SmsCancellation smsCancellation;
  @Autowired private ActionRequestContext requestContext;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  void 거절_저장의_세_호출은_한_시각으로_요청을_닫고_그_자리_링크를_거절_이유로_닫는다() {
    Room room = room();
    UUID mine = open(room.id(), PartySide.TENANT);
    UUID theirs = open(room.id(), PartySide.LANDLORD);
    UUID link =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.invited(), null, room.issuer()));
    UUID otherSeatLink =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.mine(), null, room.issuer()));

    List<UUID> closedLinks = inTx(REJECTED_AT, () -> reject(room));

    // 초대한 측(세입자)이 요청한 열린 요청만 닫힌다. 반대 방향은 그대로
    assertThat(request(mine))
        .containsEntry("status", "CLOSED")
        .containsEntry("closed_reason", "INVITE_REJECTED");
    assertThat(((Timestamp) request(mine).get("closed_at")).toInstant()).isEqualTo(REJECTED_AT);
    assertThat(request(theirs)).containsEntry("status", "WAITING");
    // 거절된 자리의 링크만 V16 CHECK의 새 이유로 닫히고, 무효 시각은 같은 행동 시각이다
    assertThat(closedLinks).containsExactly(link);
    assertThat(linkReason(link)).isEqualTo("INVITE_REJECTED");
    assertThat(linkInvalidatedAt(link)).isEqualTo(REJECTED_AT);
    assertThat(linkReason(otherSeatLink)).isNull();
  }

  @Test
  void 이미_무효였던_링크는_처음_이유와_시각이_그대로고_목록에는_들어간다() {
    Room room = room();
    UUID old =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.invited(), null, room.issuer()));
    inTx(
        AT,
        () ->
            inviteLinks.invalidateInvites(room.id(), room.invited(), LinkInvalidReason.REINVITED));
    UUID current =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.invited(), null, room.issuer()));

    List<UUID> closedLinks = inTx(REJECTED_AT, () -> reject(room));

    assertThat(closedLinks).containsExactlyInAnyOrder(old, current);
    assertThat(linkReason(old)).isEqualTo("REINVITED");
    assertThat(linkInvalidatedAt(old)).isEqualTo(AT);
    assertThat(linkReason(current)).isEqualTo("INVITE_REJECTED");
  }

  @Test
  void 거절_저장이_뒤에서_실패하면_요청_닫기와_링크_무효가_함께_되돌아간다() {
    Room room = room();
    UUID mine = open(room.id(), PartySide.TENANT);
    UUID link =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.invited(), null, room.issuer()));

    assertThatThrownBy(() ->
            inTx(
                REJECTED_AT,
                () -> {
                  reject(room);
                  // 같은 저장의 뒤 단계(자리 거절됨으로 바꾸기 등)가 실패한 것처럼
                  throw new IllegalStateException("뒤 단계 실패");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(request(mine)).containsEntry("status", "WAITING");
    assertThat(request(mine).get("closed_reason")).isNull();
    assertThat(linkReason(link)).isNull();
  }

  @Test
  void 같은_거절을_다시_처리해도_닫은_요청과_링크의_처음_시각이_그대로다() {
    Room room = room();
    UUID mine = open(room.id(), PartySide.TENANT);
    UUID link =
        inTx(AT, () -> inviteLinks.issueInvite(room.id(), room.invited(), null, room.issuer()));
    inTx(REJECTED_AT, () -> reject(room));

    inTx(REJECTED_AT.plusSeconds(60), () -> reject(room));

    assertThat(((Timestamp) request(mine).get("closed_at")).toInstant()).isEqualTo(REJECTED_AT);
    assertThat(linkInvalidatedAt(link)).isEqualTo(REJECTED_AT);
    assertThat(linkReason(link)).isEqualTo("INVITE_REJECTED");
  }

  /** room의 거절 저장이 부를 순서 그대로. 초대한 측은 호실을 만든 측(세입자)이다. */
  private List<UUID> reject(Room room) {
    waits.closeRequestedBy(room.id(), PartySide.TENANT, ClosedReason.INVITE_REJECTED);
    List<UUID> links =
        inviteLinks.invalidateInvites(room.id(), room.invited(), LinkInvalidReason.INVITE_REJECTED);
    smsCancellation.cancelPendingSms(room.id(), links);
    return links;
  }

  private UUID open(UUID room, PartySide side) {
    RequestTarget target =
        RequestTarget.of(
            TargetKind.INSPECTION,
            inspection(room, side == PartySide.TENANT ? "MOVE_IN" : "MOVE_OUT"));
    return inTx(
            AT,
            () ->
                waits.open(
                    new OpenRequestCommand(room, RequestKind.INSPECTION_CONFIRM, target, side)))
        .requestId();
  }

  private <T> T inTx(Instant at, Supplier<T> work) {
    return new TransactionTemplate(txManager)
        .execute(status -> RequestContextTestSupport.within(requestContext, at, work));
  }

  private Map<String, Object> request(UUID id) {
    return jdbc.queryForMap(
        "SELECT status, closed_reason, closed_at FROM state.response_request WHERE id = ?", id);
  }

  private String linkReason(UUID link) {
    return jdbc.queryForObject(
        "SELECT invalidated_reason FROM state.link WHERE id = ?", String.class, link);
  }

  private Instant linkInvalidatedAt(UUID link) {
    return jdbc.queryForObject(
            "SELECT invalidated_at FROM state.link WHERE id = ?", Timestamp.class, link)
        .toInstant();
  }

  /** 거주 중 호실: 만든 측(세입자, 회원) 연결됨, 초대받는 집주인 자리 초대됨. */
  private Room room() {
    UUID id = UUID.randomUUID();
    String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)"
            + " VALUES (?, ?, '거절', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        id,
        region);
    UUID issuer = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        issuer);
    UUID mine = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, member_id, status, created_at, version)"
            + " VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, 0)",
        mine,
        id,
        issuer);
    UUID invited = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', '01012345678', 'INVITED', CURRENT_TIMESTAMP, 0)",
        invited,
        id);
    return new Room(id, mine, invited, issuer);
  }

  /** 호실마다 종류별 점검은 하나라, 방향마다 다른 종류를 쓴다. */
  private UUID inspection(UUID room, String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)"
            + " VALUES (?, ?, ?, 'TENANT', 'DRAFT', CURRENT_TIMESTAMP, 0)",
        id,
        room,
        kind);
    return id;
  }

  private record Room(UUID id, UUID mine, UUID invited, UUID issuer) {}
}
