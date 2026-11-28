package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/**
 * 응답 요청 상태 전이와 호실·최근 조회. 바꾼 값이 V1 CHECK(닫힌 이유·시각은 CLOSED일 때만)를 지나 실제 PostgreSQL에 남는지, 끝난 요청은 바뀌지
 * 않는지 본다. 기다림 시작(전달 → 재통지 기한)과 기한 다시 세기도 본다. 미확인은 아직 엔티티로 만들 수 없어 JDBC로 준비한다.
 */
class ResponseRequestTransitionTest extends SchemaTestSupport {

  private static final Instant CREATED = Instant.parse("2026-11-26T01:00:00.123456Z");
  private static final Instant CLOSED_AT = Instant.parse("2026-11-26T02:00:00.654321Z");
  private static final Instant DELIVERED = Instant.parse("2026-11-26T01:05:00.111111Z");
  private static final Duration RENOTIFY = Duration.ofHours(48);
  private static final Duration UNCONFIRMED = Duration.ofHours(24);

  @Autowired private ResponseRequestRepository requests;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 답함_거둠_닫힘이_DB에_남는다() {
    UUID room = room();
    ResponseRequest answered = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    ResponseRequest withdrawn = save(room, RequestKind.CLOSE_CONFIRM, repairCase(room));
    ResponseRequest closed = save(room, RequestKind.PROGRESS_CHECK, repairCase(room));

    answered.answer(PartySide.LANDLORD);
    withdrawn.withdraw(PartySide.TENANT);
    closed.close(ClosedReason.ROOM_ENDED, CLOSED_AT);
    entityManager.flush();
    entityManager.clear();

    assertThat(row(answered)).containsEntry("status", "ANSWERED").containsEntry("closed_at", null);
    assertThat(row(withdrawn))
        .containsEntry("status", "WITHDRAWN")
        .containsEntry("closed_reason", null);
    ResponseRequest reread = requests.findById(closed.getId()).orElseThrow();
    assertThat(reread.getStatus()).isEqualTo(RequestStatus.CLOSED);
    assertThat(reread.getClosedReason()).isEqualTo(ClosedReason.ROOM_ENDED);
    assertThat(reread.getClosedAt()).isEqualTo(CLOSED_AT);
    assertThat(reread.getCreatedAt()).isEqualTo(CREATED);
  }

  @Test
  void 미확인도_열린_요청이라_답할_수_있다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    setStatus(request, "UNCONFIRMED");
    entityManager.clear();

    ResponseRequest unconfirmed = requests.findById(request.getId()).orElseThrow();
    assertThat(unconfirmed.isOpen()).isTrue();
    unconfirmed.answer(PartySide.LANDLORD);
    entityManager.flush();

    assertThat(row(request)).containsEntry("status", "ANSWERED");
  }

  @Test
  void 끝난_요청은_다시_바꾸지_않는다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    request.answer(PartySide.LANDLORD);

    assertThatThrownBy(() -> request.answer(PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> request.withdraw(PartySide.TENANT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> request.close(ClosedReason.ROOM_ENDED, CLOSED_AT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(request.getStatus()).isEqualTo(RequestStatus.ANSWERED);
  }

  @Test
  void 요청한_측은_답하지_못하고_상대는_거두지_못한다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));

    assertThatThrownBy(() -> request.answer(PartySide.TENANT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> request.withdraw(PartySide.LANDLORD))
        .isInstanceOf(IllegalStateException.class);
    assertThat(request.getStatus()).isEqualTo(RequestStatus.WAITING);
  }

  @Test
  void 끝난_제안은_다시_기다림으로_돌리지_않는다() {
    UUID room = room();
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(room));
    proposal.close(ClosedReason.AMOUNT_CHANGED, CLOSED_AT);

    assertThatThrownBy(() -> proposal.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(proposal.getStatus()).isEqualTo(RequestStatus.CLOSED);
  }

  @Test
  void 제안을_바꾸면_미확인에서_다시_기다림이_되고_이유가_지워진다() {
    UUID room = room();
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(room));
    jdbc.update(
        "UPDATE state.response_request SET status = 'UNCONFIRMED', solo_reason = 'UNCONFIRMED'"
            + " WHERE id = ?",
        proposal.getId());
    entityManager.clear();

    ResponseRequest unconfirmed = requests.findById(proposal.getId()).orElseThrow();
    assertThat(unconfirmed.getSoloReason()).isEqualTo(SoloReason.UNCONFIRMED);
    unconfirmed.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED);
    entityManager.flush();

    assertThat(row(proposal)).containsEntry("status", "WAITING").containsEntry("solo_reason", null);
  }

  @Test
  void 미확인_고친_내용_확인을_또_고치면_같은_요청이_다시_기다림이고_첫_통지_시각은_그대로다() {
    UUID room = room();
    ResponseRequest correction = save(room, RequestKind.CORRECTION_CONFIRM, repairCase(room));
    jdbc.update(
        "UPDATE state.response_request SET status = 'UNCONFIRMED', first_notified_at = ?"
            + " WHERE id = ?",
        Timestamp.from(CREATED),
        correction.getId());
    entityManager.clear();

    // 03 1-4 "미확인 뒤 다시 고침", decisions 2026-11-27(#104)
    ResponseRequest unconfirmed = requests.findById(correction.getId()).orElseThrow();
    unconfirmed.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED);
    entityManager.flush();
    entityManager.clear();

    ResponseRequest again = requests.findById(correction.getId()).orElseThrow();
    assertThat(again.getStatus()).isEqualTo(RequestStatus.WAITING);
    assertThat(again.getCreatedAt()).isEqualTo(CREATED);
    assertThat(again.getFirstNotifiedAt()).isEqualTo(CREATED);
    // 재통지 전 기한이면 그 시각부터 다시 센다(03 1-4 "기한")
    assertThat(again.getRenotifyDueAt()).isEqualTo(CLOSED_AT.plus(RENOTIFY));
  }

  @Test
  void 재통지_뒤_미확인인_고친_내용_확인을_다시_기다리게_하면_미확인_기한을_그_시각부터_72시간으로_정한다() {
    // 03 1-4 "미확인 뒤 다시 고침"(#313) + "재통지 뒤면 미확인 72시간을 다시 정함". 이전에는 d3 전이라 거절했다
    UUID room = room();
    ResponseRequest correction = save(room, RequestKind.CORRECTION_CONFIRM, repairCase(room));
    jdbc.update(
        "UPDATE state.response_request SET status = 'UNCONFIRMED', first_notified_at = ?,"
            + " unconfirmed_due_at = ? WHERE id = ?",
        Timestamp.from(CREATED),
        Timestamp.from(CLOSED_AT),
        correction.getId());
    entityManager.clear();

    ResponseRequest renotified = requests.findById(correction.getId()).orElseThrow();
    renotified.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED);

    assertThat(renotified.getStatus()).isEqualTo(RequestStatus.WAITING);
    assertThat(renotified.getUnconfirmedDueAt())
        .isEqualTo(CLOSED_AT.plus(RENOTIFY).plus(UNCONFIRMED));
  }

  @Test
  void 고친_내용_제안이_아닌_요청은_재통지_뒤_다시_세도_저장한_기한이_그대로다() {
    UUID room = room();
    ResponseRequest ask = save(room, RequestKind.PROGRESS_CHECK, repairCase(room));
    ask.startWaiting(DELIVERED, RENOTIFY);
    Instant renotifiedAt = DELIVERED.plus(RENOTIFY);
    ask.renotifyReached(renotifiedAt, UNCONFIRMED);

    ask.recount(renotifiedAt.plusSeconds(600), RENOTIFY, UNCONFIRMED);

    assertThat(ask.getRenotifyDueAt()).isEqualTo(DELIVERED.plus(RENOTIFY));
    assertThat(ask.getUnconfirmedDueAt()).isEqualTo(renotifiedAt.plus(UNCONFIRMED));
  }

  @Test
  void 고친_내용_제안이_아닌_요청은_재통지가_닿기_전_다시_세도_미확인_기한은_닿은_시각부터다() {
    UUID room = room();
    ResponseRequest ask = save(room, RequestKind.PROGRESS_CHECK, repairCase(room));
    ask.startWaiting(DELIVERED, RENOTIFY);
    Instant asked = DELIVERED.plus(RENOTIFY).plusSeconds(60);
    ask.recount(asked, RENOTIFY, UNCONFIRMED);
    Instant reached = asked.plusSeconds(30);

    ask.renotifyReached(reached, UNCONFIRMED);

    assertThat(ask.getUnconfirmedDueAt()).isEqualTo(reached.plus(UNCONFIRMED));
  }

  @Test
  void 기다리는_고친_내용_확인은_다시_기다림이_아니라_묶기다() {
    UUID room = room();
    ResponseRequest waiting = save(room, RequestKind.CORRECTION_CONFIRM, repairCase(room));

    assertThatThrownBy(() -> waiting.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 다시_기다림은_제안과_미확인_고친_내용만이다() {
    UUID room = room();
    ResponseRequest defect = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));

    assertThatThrownBy(() -> defect.rewait(CLOSED_AT, RENOTIFY, UNCONFIRMED))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- 기다림 시작(03 1-1 "타이머 시작점") ----

  @Test
  void 첫_전달에_기다림이_시작되고_재통지_기한은_그_시각_더하기_48시간이다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));

    assertThat(request.startWaiting(DELIVERED, RENOTIFY)).isTrue();
    entityManager.flush();
    entityManager.clear();

    ResponseRequest reread = requests.findById(request.getId()).orElseThrow();
    assertThat(reread.getFirstNotifiedAt()).isEqualTo(DELIVERED);
    assertThat(reread.getRenotifyDueAt()).isEqualTo(DELIVERED.plus(RENOTIFY));
    assertThat(reread.getUnconfirmedDueAt()).isNull();
    assertThat(reread.getStatus()).isEqualTo(RequestStatus.WAITING);
  }

  @Test
  void 번호를_고쳐_다시_시작하면_기다림_시작과_두_기한이_비고_새_전달부터_다시_센다() {
    // #370 T-2: 같은 요청 ID, 기다림만 처음으로
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    request.startWaiting(DELIVERED, RENOTIFY);
    request.renotifyReached(DELIVERED.plus(RENOTIFY), UNCONFIRMED);

    assertThat(request.restartForNewNumber()).isTrue();
    assertThat(request.getFirstNotifiedAt()).isNull();
    assertThat(request.getRenotifyDueAt()).isNull();
    assertThat(request.getUnconfirmedDueAt()).isNull();
    assertThat(request.getStatus()).isEqualTo(RequestStatus.WAITING);

    Instant fresh = DELIVERED.plus(Duration.ofHours(60));
    assertThat(request.startWaiting(fresh, RENOTIFY)).isTrue();
    assertThat(request.getRenotifyDueAt()).isEqualTo(fresh.plus(RENOTIFY));
  }

  @Test
  void 시작_전이거나_기다림이_아니면_다시_시작하지_않는다() {
    UUID room = room();
    ResponseRequest notStarted = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    assertThat(notStarted.restartForNewNumber()).isFalse();

    ResponseRequest answered = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    answered.startWaiting(DELIVERED, RENOTIFY);
    answered.answer(PartySide.LANDLORD);
    assertThat(answered.restartForNewNumber()).isFalse();
    assertThat(answered.getFirstNotifiedAt()).isEqualTo(DELIVERED);
  }

  @Test
  void 같은_증거가_다시_오거나_다른_채널이_뒤에_와도_기한을_늘리지_않는다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    request.startWaiting(DELIVERED, RENOTIFY);

    assertThat(request.startWaiting(DELIVERED, RENOTIFY)).isFalse();
    assertThat(request.startWaiting(DELIVERED.plusSeconds(600), RENOTIFY)).isFalse();

    assertThat(request.getFirstNotifiedAt()).isEqualTo(DELIVERED);
    assertThat(request.getRenotifyDueAt()).isEqualTo(DELIVERED.plus(RENOTIFY));
  }

  @Test
  void 더_이른_증거가_늦게_오면_시작_시각만_당기고_저장한_기한은_그대로다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    request.startWaiting(DELIVERED, RENOTIFY);
    Instant viewedEarlier = DELIVERED.minusSeconds(5);

    assertThat(request.startWaiting(viewedEarlier, RENOTIFY)).isFalse();

    // first_notified_at의 정의는 min(전달됨, 링크 열람, 알림 열람, 05). 기한은 받는 쪽에 불리하게 당기지 않는다
    assertThat(request.getFirstNotifiedAt()).isEqualTo(viewedEarlier);
    assertThat(request.getRenotifyDueAt()).isEqualTo(DELIVERED.plus(RENOTIFY));
  }

  // ---- 재통지가 닿으면 미확인 기한(06 0-1 wait.unconfirmed, 03 1-1 "미확인이 붙는 조건") ----

  @Test
  void 재통지가_닿으면_닿은_시각부터_미확인_기한이_생기고_다시_와도_바꾸지_않는다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    request.startWaiting(DELIVERED, RENOTIFY);
    Instant renotified = DELIVERED.plus(RENOTIFY).plusSeconds(90);

    assertThat(request.renotifyReached(renotified, UNCONFIRMED)).isTrue();
    assertThat(request.renotifyReached(renotified.plusSeconds(600), UNCONFIRMED)).isFalse();
    assertThat(request.renotifyReached(renotified.minusSeconds(30), UNCONFIRMED)).isFalse();
    entityManager.flush();
    entityManager.clear();

    ResponseRequest reread = requests.findById(request.getId()).orElseThrow();
    assertThat(reread.getUnconfirmedDueAt()).isEqualTo(renotified.plus(UNCONFIRMED));
    assertThat(reread.getRenotifyDueAt()).isEqualTo(DELIVERED.plus(RENOTIFY));
    assertThat(reread.getStatus()).isEqualTo(RequestStatus.WAITING);
  }

  @Test
  void 기다림이_시작되지_않았거나_끝난_요청에는_재통지가_닿아도_미확인_기한이_없다() {
    UUID room = room();
    ResponseRequest notStarted = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    ResponseRequest answered = save(room, RequestKind.CLOSE_CONFIRM, repairCase(room));
    answered.startWaiting(DELIVERED, RENOTIFY);
    answered.answer(
        answered.getRequestedBySide() == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT);

    assertThat(notStarted.renotifyReached(DELIVERED, UNCONFIRMED)).isFalse();
    assertThat(answered.renotifyReached(DELIVERED, UNCONFIRMED)).isFalse();
    assertThat(notStarted.getUnconfirmedDueAt()).isNull();
    assertThat(answered.getUnconfirmedDueAt()).isNull();
  }

  @Test
  void 재통지가_닿기_전에_더해_기한을_다시_셌으면_더한_시각부터_72시간보다_이르지_않다() {
    // 03 1-4 "기한": 재통지 뒤 묶으면 미확인 72시간을 다시 정함. 닿기 전 다시 센 재통지 기한 + 미확인 = 더한 시각 + 72시간
    UUID room = room();
    ResponseRequest request =
        save(room, RequestKind.CORRECTION_CONFIRM, RequestTarget.roomRecord(room));
    request.startWaiting(DELIVERED, RENOTIFY);
    Instant added = DELIVERED.plus(RENOTIFY).plusSeconds(60);
    request.recount(added, RENOTIFY, UNCONFIRMED);

    request.renotifyReached(added.plusSeconds(30), UNCONFIRMED);

    assertThat(request.getUnconfirmedDueAt()).isEqualTo(added.plus(RENOTIFY).plus(UNCONFIRMED));
  }

  @Test
  void 재통지가_닿은_기다림에_더하면_미확인_기한을_더한_시각부터_72시간으로_다시_정한다() {
    // 03 1-4 "기한": 재통지 뒤면 미확인 72시간을 다시 정함. 재통지는 다시 가지 않는다(요청당 한 번)
    UUID room = room();
    ResponseRequest request =
        save(room, RequestKind.CORRECTION_CONFIRM, RequestTarget.roomRecord(room));
    request.startWaiting(DELIVERED, RENOTIFY);
    request.renotifyReached(DELIVERED.plus(RENOTIFY), UNCONFIRMED);
    Instant added = DELIVERED.plus(RENOTIFY).plusSeconds(3_600);

    request.recount(added, RENOTIFY, UNCONFIRMED);

    assertThat(request.getUnconfirmedDueAt()).isEqualTo(added.plus(RENOTIFY).plus(UNCONFIRMED));
    assertThat(request.getStatus()).isEqualTo(RequestStatus.WAITING);
  }

  @Test
  void 재통지가_닿은_제안을_바꿔도_미확인_기한을_바꾼_시각부터_다시_정한다() {
    UUID room = room();
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, RequestTarget.roomRecord(room));
    proposal.startWaiting(DELIVERED, RENOTIFY);
    proposal.renotifyReached(DELIVERED.plus(RENOTIFY), UNCONFIRMED);
    Instant changed = DELIVERED.plus(RENOTIFY).plusSeconds(60);

    proposal.rewait(changed, RENOTIFY, UNCONFIRMED);

    assertThat(proposal.getUnconfirmedDueAt()).isEqualTo(changed.plus(RENOTIFY).plus(UNCONFIRMED));
  }

  @Test
  void 끝난_요청에_늦게_온_전달은_버린다() {
    UUID room = room();
    ResponseRequest answered = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    ResponseRequest withdrawn = save(room, RequestKind.CLOSE_CONFIRM, repairCase(room));
    ResponseRequest closed = save(room, RequestKind.PROGRESS_CHECK, repairCase(room));
    answered.answer(PartySide.LANDLORD);
    withdrawn.withdraw(PartySide.TENANT);
    closed.close(ClosedReason.ROOM_ENDED, CLOSED_AT);

    for (ResponseRequest ended : java.util.List.of(answered, withdrawn, closed)) {
      assertThat(ended.startWaiting(DELIVERED, RENOTIFY)).isFalse();
      assertThat(ended.getFirstNotifiedAt()).isNull();
      assertThat(ended.getRenotifyDueAt()).isNull();
    }
  }

  @Test
  void 다시_세기는_시작_전이면_그대로이고_재통지_전이면_재통지_기한을_다시_정한다() {
    UUID room = room();
    ResponseRequest request = save(room, RequestKind.CORRECTION_CONFIRM, repairCase(room));

    request.recount(CLOSED_AT, RENOTIFY, UNCONFIRMED);
    assertThat(request.hasDeadline()).isFalse();

    request.startWaiting(DELIVERED, RENOTIFY);
    Instant added = DELIVERED.plusSeconds(3600);
    request.recount(added, RENOTIFY, UNCONFIRMED);

    assertThat(request.getFirstNotifiedAt()).isEqualTo(DELIVERED);
    assertThat(request.getRenotifyDueAt()).isEqualTo(added.plus(RENOTIFY));
  }

  @Test
  void 재통지가_닿은_기다림의_제안_바꾸기는_미확인_기한을_다시_정한다() {
    // 이전에는 d3 전이라 거절했다. 03 1-4 "재통지 뒤면 미확인 72시간을 다시 정함"(decisions 2026-11-28 재통지 작업자)
    UUID room = room();
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(room));
    jdbc.update(
        "UPDATE state.response_request SET first_notified_at = ?, unconfirmed_due_at = ?"
            + " WHERE id = ?",
        Timestamp.from(DELIVERED),
        Timestamp.from(CLOSED_AT),
        proposal.getId());
    entityManager.clear();

    ResponseRequest renotified = requests.findById(proposal.getId()).orElseThrow();
    renotified.rewait(DELIVERED, RENOTIFY, UNCONFIRMED);

    assertThat(renotified.getUnconfirmedDueAt())
        .isEqualTo(DELIVERED.plus(RENOTIFY).plus(UNCONFIRMED));
    assertThat(renotified.getStatus()).isEqualTo(RequestStatus.WAITING);
  }

  @Test
  void 제안을_바꾸면_바꾼_시각부터_재통지_기한을_다시_센다() {
    UUID room = room();
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(room));
    proposal.startWaiting(DELIVERED, RENOTIFY);
    Instant changed = DELIVERED.plusSeconds(60);

    proposal.rewait(changed, RENOTIFY, UNCONFIRMED);
    entityManager.flush();

    assertThat(proposal.getRenotifyDueAt()).isEqualTo(changed.plus(RENOTIFY));
    assertThat(proposal.getFirstNotifiedAt()).isEqualTo(DELIVERED);
  }

  @Test
  void 호실에서_한_측이_보낸_열린_요청만_찾는다() {
    UUID room = room();
    UUID otherRoom = room();
    ResponseRequest waiting = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    ResponseRequest unconfirmed = save(room, RequestKind.CLOSE_CONFIRM, repairCase(room));
    setStatus(unconfirmed, "UNCONFIRMED");
    ResponseRequest answered = save(room, RequestKind.PROGRESS_CHECK, repairCase(room));
    setStatus(answered, "ANSWERED");
    requests.saveAndFlush(
        new ResponseRequest(
            room, RequestKind.DEFECT_CONFIRM, repairCase(room), PartySide.LANDLORD, CREATED));
    save(otherRoom, RequestKind.DEFECT_CONFIRM, repairCase(otherRoom));
    entityManager.clear();

    assertThat(requests.findOpenRequestedBy(room, PartySide.TENANT))
        .extracting(ResponseRequest::getId)
        .containsExactlyInAnyOrder(waiting.getId(), unconfirmed.getId());
  }

  @Test
  void 가장_최근_요청은_닫힌_것도_포함하고_새로_연_것을_준다() {
    UUID room = room();
    RequestTarget target = repairCase(room);
    ResponseRequest old = save(room, RequestKind.CLOSE_CONFIRM, target);
    old.answer(PartySide.LANDLORD);
    entityManager.flush(); // 새 WAITING을 넣기 전에 옛 요청이 끝나 있어야 V1 유니크에 걸리지 않음
    ResponseRequest newer =
        requests.saveAndFlush(
            new ResponseRequest(
                room, RequestKind.CLOSE_CONFIRM, target, PartySide.LANDLORD, CLOSED_AT));
    entityManager.clear();

    assertThat(requests.findLatest(RequestKind.CLOSE_CONFIRM, target))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(newer.getId());
    assertThat(requests.findLatest(RequestKind.DEFECT_CONFIRM, target)).isEmpty();
    assertThatThrownBy(() ->
            requests.findLatest(
                RequestKind.INSPECTION_CONFIRM,
                RequestTarget.of(TargetKind.INSPECTION, UUID.randomUUID())))
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  private ResponseRequest save(UUID room, RequestKind kind, RequestTarget target) {
    return requests.saveAndFlush(
        new ResponseRequest(room, kind, target, PartySide.TENANT, CREATED));
  }

  private RequestTarget repairCase(UUID room) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, repair(room));
  }

  private void setStatus(ResponseRequest request, String status) {
    jdbc.update(
        "UPDATE state.response_request SET status = ? WHERE id = ?", status, request.getId());
  }

  private Map<String, Object> row(ResponseRequest request) {
    entityManager.flush();
    return jdbc.queryForMap(
        "SELECT status, closed_reason, closed_at, solo_reason FROM state.response_request"
            + " WHERE id = ?",
        request.getId());
  }
}
