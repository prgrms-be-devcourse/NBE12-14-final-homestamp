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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/**
 * 응답 요청 상태 전이와 호실·최근 조회. 바꾼 값이 V1 CHECK(닫힌 이유·시각은 CLOSED일 때만)를 지나 실제 PostgreSQL에 남는지, 끝난 요청은 바뀌지
 * 않는지 본다. 미확인·기한은 아직 엔티티로 만들 수 없어 JDBC로 준비한다.
 */
class ResponseRequestTransitionTest extends SchemaTestSupport {

  private static final Instant CREATED = Instant.parse("2026-11-26T01:00:00.123456Z");
  private static final Instant CLOSED_AT = Instant.parse("2026-11-26T02:00:00.654321Z");

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

    assertThatThrownBy(proposal::rewait).isInstanceOf(IllegalStateException.class);
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
    unconfirmed.rewait();
    entityManager.flush();

    assertThat(row(proposal)).containsEntry("status", "WAITING").containsEntry("solo_reason", null);
  }

  @Test
  void 다시_기다림은_제안만이고_기한이_있으면_타이머_전이라_거절한다() {
    UUID room = room();
    ResponseRequest defect = save(room, RequestKind.DEFECT_CONFIRM, repairCase(room));
    assertThatThrownBy(defect::rewait).isInstanceOf(IllegalStateException.class);

    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(room));
    jdbc.update(
        "UPDATE state.response_request SET unconfirmed_due_at = ? WHERE id = ?",
        Timestamp.from(CLOSED_AT),
        proposal.getId());
    entityManager.clear();

    ResponseRequest withDeadline = requests.findById(proposal.getId()).orElseThrow();
    assertThat(withDeadline.hasDeadline()).isTrue();
    assertThatThrownBy(withDeadline::rewait).isInstanceOf(IllegalStateException.class);
    assertThat(withDeadline.getStatus()).isEqualTo(RequestStatus.WAITING);
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
