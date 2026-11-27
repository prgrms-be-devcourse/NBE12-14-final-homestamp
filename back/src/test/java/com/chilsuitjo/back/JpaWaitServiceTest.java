package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.RequestContextTestSupport;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestHandle;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.dto.SoloCheck;
import com.chilsuitjo.back.notification.dto.SoloQuery;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import com.chilsuitjo.back.notification.service.JpaWaitService;
import com.chilsuitjo.back.notification.service.WaitService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 실제 DB 응답 요청({@link JpaWaitService})이 {@link WaitService} Javadoc·decisions 2026-11-19·11-26 계약대로
 * PostgreSQL에 요청을 남기고 바꾸는지 본다. 바꾸는 메서드는 {@code save} 안처럼 행동 시각 창구를 연 채 부른다. 조회는 쓰기와 같은 행을 읽는다.
 */
class JpaWaitServiceTest extends SchemaTestSupport {

  private static final Instant AT = Instant.parse("2026-11-26T03:00:00.123456Z");
  private static final Instant LATER = Instant.parse("2026-11-26T04:00:00.654321Z");

  @Autowired private JpaWaitService waits;
  @Autowired private ActionRequestContext requestContext;
  @Autowired private ResponseRequestRepository requests;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 열면_요청이_기다림으로_남고_만든_시각은_행동_시각이다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);

    RequestHandle handle =
        at(
            AT,
            () -> waits.open(command(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT)));

    assertThat(handle.created()).isTrue();
    Map<String, Object> row = row(handle.requestId());
    assertThat(row).containsEntry("status", "WAITING").containsEntry("first_notified_at", null);
    assertThat(row.get("unconfirmed_due_at")).isNull();
    assertThat(waits.latest(RequestKind.DEFECT_CONFIRM, repair))
        .map(RequestView::openedAt)
        .contains(AT);
  }

  @Test
  void 열린_요청이_있으면_미확인이어도_새로_만들지_않는다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle first = open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);
    setStatus(first.requestId(), "UNCONFIRMED");

    RequestHandle second = open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);

    assertThat(second.created()).isFalse();
    assertThat(second.requestId()).isEqualTo(first.requestId());
    assertThat(count(room)).isEqualTo(1);
  }

  @Test
  void 상대_제안이_열린_채로_열면_역제안이라_거절한다() {
    UUID room = room();
    RequestTarget agenda = repairCase(room);
    open(room, RequestKind.PROPOSAL, agenda, PartySide.TENANT);

    assertThatThrownBy(() -> open(room, RequestKind.PROPOSAL, agenda, PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);

    // answered 다음 open이면 새 요청(05 proposal ③)
    at(
        AT,
        () -> {
          waits.answered(agenda, RequestKind.PROPOSAL, PartySide.LANDLORD);
          return null;
        });
    assertThat(open(room, RequestKind.PROPOSAL, agenda, PartySide.LANDLORD).created()).isTrue();
  }

  @Test
  void 답은_상대가_해야_하고_끝난_요청은_그대로다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle request = open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);

    answered(repair, RequestKind.DEFECT_CONFIRM, PartySide.TENANT); // 요청한 측: 답이 아님
    assertThat(status(request)).isEqualTo("WAITING");

    answered(repair, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);
    assertThat(status(request)).isEqualTo("ANSWERED");

    // 이미 끝났으면 아무것도 하지 않는다(닫힌 요청을 되살리거나 바꾸지 않음)
    answered(repair, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);
    at(
        AT,
        () -> {
          waits.close(repair, RequestKind.DEFECT_CONFIRM, ClosedReason.ROOM_ENDED);
          return null;
        });
    assertThat(status(request)).isEqualTo("ANSWERED");
  }

  @Test
  void 거둠은_요청한_측만_된다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle request = open(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);

    withdrawn(repair, PartySide.LANDLORD);
    assertThat(status(request)).isEqualTo("WAITING");

    withdrawn(repair, PartySide.TENANT);
    assertThat(status(request)).isEqualTo("WITHDRAWN");
  }

  @Test
  void 점검_확인은_방향마다_따로이고_닫기는_두_방향을_모두_닫는다() {
    UUID room = room();
    RequestTarget inspection = RequestTarget.of(TargetKind.INSPECTION, inspection(room));
    RequestHandle byTenant =
        open(room, RequestKind.INSPECTION_CONFIRM, inspection, PartySide.TENANT);
    RequestHandle byLandlord =
        open(room, RequestKind.INSPECTION_CONFIRM, inspection, PartySide.LANDLORD);
    assertThat(byLandlord.requestId()).isNotEqualTo(byTenant.requestId());

    // 집주인이 답하면 세입자가 보낸 쪽만 답함
    answered(inspection, RequestKind.INSPECTION_CONFIRM, PartySide.LANDLORD);
    assertThat(status(byTenant)).isEqualTo("ANSWERED");
    assertThat(status(byLandlord)).isEqualTo("WAITING");

    at(
        LATER,
        () -> {
          waits.close(inspection, RequestKind.INSPECTION_CONFIRM, ClosedReason.SHARING_STOPPED);
          return null;
        });
    Map<String, Object> closed = row(byLandlord.requestId());
    assertThat(closed)
        .containsEntry("status", "CLOSED")
        .containsEntry("closed_reason", "SHARING_STOPPED");
    assertThat(((java.sql.Timestamp) closed.get("closed_at")).toInstant()).isEqualTo(LATER);
    assertThat(status(byTenant)).isEqualTo("ANSWERED");
  }

  @Test
  void restart는_없거나_닫혔으면_새로_열고_열려_있으면_묶는다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);

    RequestHandle first = restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    assertThat(first.created()).isTrue();

    RequestHandle bound = restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    assertThat(bound.created()).isFalse();
    assertThat(bound.requestId()).isEqualTo(first.requestId());

    answered(repair, RequestKind.CORRECTION_CONFIRM, PartySide.LANDLORD);
    RequestHandle reopened =
        restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    assertThat(reopened.created()).isTrue();
    assertThat(status(first)).isEqualTo("ANSWERED");
  }

  @Test
  void 제안을_바꾸면_미확인이던_같은_요청이_다시_기다림이다() {
    UUID room = room();
    RequestTarget agenda = repairCase(room);
    RequestHandle proposal = open(room, RequestKind.PROPOSAL, agenda, PartySide.TENANT);
    setStatus(proposal.requestId(), "UNCONFIRMED");

    RequestHandle changed = restart(room, RequestKind.PROPOSAL, agenda, PartySide.TENANT);

    assertThat(changed.requestId()).isEqualTo(proposal.requestId());
    assertThat(status(proposal)).isEqualTo("WAITING");
    assertThatThrownBy(() -> restart(room, RequestKind.PROPOSAL, agenda, PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 미확인_고친_내용_확인에_또_고치면_같은_요청이_다시_기다림이다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle first = open(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    setStatus(first.requestId(), "UNCONFIRMED");

    // 03 1-4 "미확인 뒤 다시 고침", decisions 2026-11-27(#104): 새 요청을 열지 않는다
    RequestHandle again = restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);

    assertThat(again.created()).isFalse();
    assertThat(again.requestId()).isEqualTo(first.requestId());
    assertThat(status(first)).isEqualTo("WAITING");
    assertThat(count(room)).isEqualTo(1);
  }

  @Test
  void 열리는_요청은_미확인이어도_묶기만_하고_상태를_그대로_둔다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle defect = open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);
    setStatus(defect.requestId(), "UNCONFIRMED");

    restart(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);

    assertThat(status(defect)).isEqualTo("UNCONFIRMED");
  }

  @Test
  void 기다림이_시작된_고친_내용_확인에_더하면_더한_시각부터_재통지_기한을_다시_센다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle request = open(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    started(request.requestId(), AT);

    restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);

    // 03 1-4 "기한": 재통지 전이면 재통지 48시간을 마지막으로 더한 시각부터 다시 정함
    assertThat(row(request.requestId()))
        .containsEntry("renotify_due_at", Timestamp.from(LATER.plus(Duration.ofHours(48))))
        .containsEntry("first_notified_at", Timestamp.from(AT));
  }

  @Test
  void 기다림이_시작된_열리는_요청은_보충해도_기한이_그대로다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle defect = open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);
    started(defect.requestId(), AT);

    restart(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);

    // 03 1-4 "열리는 요청의 기한은 보낸 뒤 고정"
    assertThat(row(defect.requestId()))
        .containsEntry("renotify_due_at", Timestamp.from(AT.plus(Duration.ofHours(48))));
  }

  @Test
  void 재통지_뒤_미확인이_된_고친_내용에_더하면_기한_다시_세기를_타이머_전이라_거절한다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    RequestHandle request = open(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT);
    entityManager.flush();
    jdbc.update(
        "UPDATE state.response_request SET status = 'UNCONFIRMED', first_notified_at = ?,"
            + " unconfirmed_due_at = ? WHERE id = ?",
        Timestamp.from(AT),
        Timestamp.from(AT.plus(Duration.ofHours(72))),
        request.requestId());
    entityManager.clear();

    // 같은 요청을 다시 기다림으로 돌리는 것은 정했지만(#104), 재통지 뒤 "미확인 72시간을 다시 정함"(03 1-4 "기한")을 어떻게 저장할지는
    // 기다림 타이머(d3)에서 정한다. 조용히 옛 기한을 남기지 않고 거절한다. 타이머 전이라 운영에서는 미확인이 생기지 않는다
    assertThatThrownBy(
            () -> restart(room, RequestKind.CORRECTION_CONFIRM, repair, PartySide.TENANT))
        .isInstanceOf(IllegalStateException.class);
    entityManager.clear();
    assertThat(row(request.requestId()))
        .containsEntry("status", "UNCONFIRMED")
        .containsEntry("unconfirmed_due_at", Timestamp.from(AT.plus(Duration.ofHours(72))));
  }

  @Test
  void 호실_단계에서_열_수_없는_종류는_restart가_거절한다() {
    UUID room = room();
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    assertThat(waits.canOpen(room, RequestKind.DEFECT_CONFIRM)).isFalse();
    assertThat(waits.canOpen(room, RequestKind.CORRECTION_CONFIRM)).isTrue();
    assertThatThrownBy(
            () -> restart(room, RequestKind.DEFECT_CONFIRM, repairCase(room), PartySide.TENANT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count(room)).isZero();
  }

  @Test
  void 일괄_닫기는_범위가_정해지기_전이라_조용히_넘어가지_않는다() {
    UUID room = room();

    assertThatThrownBy(() ->
            waits.closeAll(
                room, ClosedReason.ROOM_ENDED, java.util.Set.of(RequestKind.DEFECT_CONFIRM)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void 조회는_쓰기와_같은_행을_읽는다() {
    UUID room = room();
    UUID otherRoom = room();
    RequestTarget repair = repairCase(room);
    open(room, RequestKind.DEFECT_CONFIRM, repair, PartySide.TENANT);
    RequestHandle unconfirmed =
        open(room, RequestKind.CLOSE_CONFIRM, repairCase(room), PartySide.TENANT);
    setStatus(unconfirmed.requestId(), "UNCONFIRMED");
    open(room, RequestKind.CLOSE_CONFIRM, repairCase(room), PartySide.LANDLORD);
    open(otherRoom, RequestKind.DEFECT_CONFIRM, repairCase(otherRoom), PartySide.TENANT);
    RequestTarget done = repairCase(room);
    open(room, RequestKind.DEFECT_CONFIRM, done, PartySide.TENANT);
    answered(done, RequestKind.DEFECT_CONFIRM, PartySide.LANDLORD);

    assertThat(waits.awaiting(room, PartySide.LANDLORD))
        .extracting(RequestView::status)
        .containsExactlyInAnyOrder(RequestStatus.WAITING, RequestStatus.UNCONFIRMED);
    assertThat(waits.latest(RequestKind.DEFECT_CONFIRM, done))
        .map(RequestView::status)
        .contains(RequestStatus.ANSWERED);
    assertThatThrownBy(() ->
            waits.latest(
                RequestKind.INSPECTION_CONFIRM,
                RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 요청_기준_혼자_진행은_미확인일_때만_열린다() {
    UUID room = room();
    RequestTarget repair = repairCase(room);
    SoloQuery query = new SoloQuery(room, RequestKind.CLOSE_CONFIRM, repair, PartySide.TENANT);
    assertThat(waits.canGoSolo(query).allowed()).isFalse();

    RequestHandle request = open(room, RequestKind.CLOSE_CONFIRM, repair, PartySide.TENANT);
    assertThat(waits.canGoSolo(query).allowed()).isFalse();

    setStatus(request.requestId(), "UNCONFIRMED");
    SoloCheck check = waits.canGoSolo(query);
    assertThat(check.allowed()).isTrue();
    assertThat(check.reason()).isEqualTo(SoloReason.UNCONFIRMED);
    // 상대 쪽(요청하지 않은 측)에는 열리지 않는다
    assertThat(
            waits
                .canGoSolo(
                    new SoloQuery(room, RequestKind.CLOSE_CONFIRM, repair, PartySide.LANDLORD))
                .allowed())
        .isFalse();
  }

  // ---- 도우미 ----

  private RequestHandle open(UUID room, RequestKind kind, RequestTarget target, PartySide side) {
    return at(AT, () -> waits.open(command(room, kind, target, side)));
  }

  private RequestHandle restart(UUID room, RequestKind kind, RequestTarget target, PartySide side) {
    return at(LATER, () -> waits.restart(command(room, kind, target, side)));
  }

  private void answered(RequestTarget target, RequestKind kind, PartySide side) {
    at(
        LATER,
        () -> {
          waits.answered(target, kind, side);
          return null;
        });
  }

  private void withdrawn(RequestTarget target, PartySide side) {
    at(
        LATER,
        () -> {
          waits.withdrawn(target, RequestKind.CORRECTION_CONFIRM, side);
          return null;
        });
  }

  /** 통지가 닿은 것처럼 기다림을 시작한다(작업자가 같은 엔티티 메서드를 부름). */
  private void started(UUID requestId, Instant at) {
    entityManager.flush();
    requests.findById(requestId).orElseThrow().startWaiting(at, Duration.ofHours(48));
    entityManager.flush();
  }

  private <T> T at(Instant at, Supplier<T> work) {
    T result = RequestContextTestSupport.within(requestContext, at, work);
    entityManager.flush();
    return result;
  }

  private static OpenRequestCommand command(
      UUID room, RequestKind kind, RequestTarget target, PartySide side) {
    return new OpenRequestCommand(room, kind, target, side);
  }

  private RequestTarget repairCase(UUID room) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, repair(room));
  }

  private UUID inspection(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inspection (id, room_record_id, kind, author_side, status, created_at, version)
        VALUES (?, ?, 'MOVE_IN', 'TENANT', 'DRAFT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }

  /** 미확인은 아직 타이머가 없어 JDBC로 만든다. 1차 캐시를 비워 다음 조회가 DB 값을 읽게 한다. */
  private void setStatus(UUID requestId, String status) {
    entityManager.flush();
    jdbc.update("UPDATE state.response_request SET status = ? WHERE id = ?", status, requestId);
    entityManager.clear();
  }

  private String status(RequestHandle handle) {
    return (String) row(handle.requestId()).get("status");
  }

  private Map<String, Object> row(UUID requestId) {
    entityManager.flush();
    return jdbc.queryForMap(
        "SELECT status, closed_reason, closed_at, first_notified_at, renotify_due_at,"
            + " unconfirmed_due_at"
            + " FROM state.response_request WHERE id = ?",
        requestId);
  }

  private int count(UUID room) {
    entityManager.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.response_request WHERE room_record_id = ?",
        Integer.class,
        room);
  }
}
