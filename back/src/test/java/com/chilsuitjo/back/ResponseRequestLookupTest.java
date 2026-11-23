package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;

/**
 * 응답 요청 조회 계약(#117 5단계). 조회 조건이 V1 유니크와 같은 뜻인지(대상 칸 NULL 비교, 상태, 종류별 키) 실제 PostgreSQL에서 본다.
 *
 * <p>엔티티는 아직 WAITING만 만들 수 있어서, 미확인·답함 같은 상태는 JDBC로 바꿔 준비하고 1차 캐시를 비운 뒤 조회한다.
 */
class ResponseRequestLookupTest extends SchemaTestSupport {

  private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");

  @Autowired private ResponseRequestRepository requests;
  @PersistenceContext private EntityManager entityManager;

  @Test
  void 일반_요청은_대상과_종류가_정확히_같은_WAITING만_찾는다() {
    UUID room = room();
    UUID repair = repair(room);
    UUID otherRepair = repair(room);
    UUID otherRoom = room();
    UUID repairInOtherRoom = repair(otherRoom);
    ResponseRequest wanted = save(room, RequestKind.DEFECT_CONFIRM, repairCase(repair));
    save(room, RequestKind.CLOSE_CONFIRM, repairCase(repair)); // 같은 대상, 다른 종류
    save(room, RequestKind.DEFECT_CONFIRM, repairCase(otherRepair)); // 같은 종류, 다른 대상
    save(otherRoom, RequestKind.DEFECT_CONFIRM, repairCase(repairInOtherRoom)); // 다른 호실
    entityManager.clear();

    assertThat(requests.findWaiting(RequestKind.DEFECT_CONFIRM, repairCase(repair)))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(wanted.getId());
    assertThat(requests.findWaiting(RequestKind.PROGRESS_CHECK, repairCase(repair))).isEmpty();
  }

  @Test
  void 호실_기록_대상은_대상_칸이_모두_빈_그_호실의_요청만_찾는다() {
    UUID room = room();
    UUID otherRoom = room();
    ResponseRequest wanted = save(room, RequestKind.END_REQUEST, RequestTarget.roomRecord(room));
    save(otherRoom, RequestKind.END_REQUEST, RequestTarget.roomRecord(otherRoom));
    entityManager.clear();

    assertThat(requests.findWaiting(RequestKind.END_REQUEST, RequestTarget.roomRecord(room)))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(wanted.getId());
    // 칸이 모두 빈 요청을 보통 대상(수리 건)으로 물으면 찾지 않는다. NULL이 "모든 대상"으로 쓰이지 않는다
    assertThat(
            requests.findByStatusIn(
                RequestKind.END_REQUEST,
                repairCase(repair(room)),
                EnumSet.allOf(RequestStatus.class)))
        .isEmpty();
  }

  @Test
  void 보통_대상_요청은_호실_기록_대상으로_물으면_찾지_않는다() {
    UUID room = room();
    save(room, RequestKind.PROGRESS_CHECK, repairCase(repair(room)));
    entityManager.clear();

    assertThat(
            requests.findByStatusIn(
                RequestKind.PROGRESS_CHECK,
                RequestTarget.roomRecord(room),
                EnumSet.allOf(RequestStatus.class)))
        .isEmpty();
  }

  @Test
  void 미확인과_기다림이_함께_있으면_WAITING_조회는_기다림만_목록은_둘_다_준다() {
    // 일반 종류의 유니크는 WAITING만 본다. 그래서 같은 대상에 미확인 하나와 기다림 하나가 함께 있을 수 있다
    UUID room = room();
    UUID repair = repair(room);
    ResponseRequest unconfirmed = save(room, RequestKind.DEFECT_CONFIRM, repairCase(repair));
    setStatus(unconfirmed, "UNCONFIRMED");
    ResponseRequest waiting = save(room, RequestKind.DEFECT_CONFIRM, repairCase(repair));
    ResponseRequest answered = save(room, RequestKind.DEFECT_CONFIRM, repairCase(repair(room)));
    setStatus(answered, "ANSWERED");
    entityManager.clear();

    assertThat(requests.findWaiting(RequestKind.DEFECT_CONFIRM, repairCase(repair)))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(waiting.getId());
    // 목록은 실제로 있는 두 건을 하나로 줄이지 않는다
    assertThat(
            requests.findByStatusIn(
                RequestKind.DEFECT_CONFIRM,
                repairCase(repair),
                EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED)))
        .extracting(ResponseRequest::getId)
        .containsExactlyInAnyOrder(unconfirmed.getId(), waiting.getId());
    assertThat(
            requests.findByStatusIn(
                RequestKind.DEFECT_CONFIRM, repairCase(repair), Set.of(RequestStatus.UNCONFIRMED)))
        .extracting(ResponseRequest::getId)
        .containsExactly(unconfirmed.getId());
  }

  @Test
  void 제안_요청은_안건마다_기다림과_미확인을_합쳐_하나를_찾는다() {
    UUID room = room();
    UUID repair = repair(room);
    ResponseRequest proposal = save(room, RequestKind.PROPOSAL, repairCase(repair));
    setStatus(proposal, "UNCONFIRMED"); // 미확인 제안도 기다리는 안(decisions 2026-11-13)
    entityManager.clear();

    assertThat(requests.findWaitingOrUnconfirmedProposal(repairCase(repair)))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(proposal.getId());
    assertThat(requests.findWaitingOrUnconfirmedProposal(repairCase(repair(room)))).isEmpty();
  }

  @Test
  void 점검_확인은_요청한_측마다_찾고_목록은_두_방향을_모두_준다() {
    UUID room = room();
    UUID inspection = inspection(room);
    ResponseRequest byTenant = save(room, RequestKind.INSPECTION_CONFIRM, inspectionOf(inspection));
    ResponseRequest byLandlord =
        requests.saveAndFlush(
            new ResponseRequest(
                room,
                RequestKind.INSPECTION_CONFIRM,
                inspectionOf(inspection),
                PartySide.LANDLORD,
                NOW));
    entityManager.clear();

    assertThat(requests.findWaitingInspectionConfirm(inspection, PartySide.TENANT))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(byTenant.getId());
    assertThat(requests.findWaitingInspectionConfirm(inspection, PartySide.LANDLORD))
        .get()
        .extracting(ResponseRequest::getId)
        .isEqualTo(byLandlord.getId());
    // 측 없이 물으면 두 건이다(그래서 단일 조회는 측을 받는다)
    assertThat(
            requests.findByStatusIn(
                RequestKind.INSPECTION_CONFIRM,
                inspectionOf(inspection),
                Set.of(RequestStatus.WAITING)))
        .hasSize(2);
  }

  @Test
  void 유니크가_한_건을_보장하지_않는_조회는_단일_조회로_받지_않는다() {
    UUID room = room();
    RequestTarget repair = repairCase(repair(room));

    assertRejected(() -> requests.findWaiting(RequestKind.PROPOSAL, repair));
    assertRejected(() -> requests.findWaiting(RequestKind.INSPECTION_CONFIRM, repair));
    assertRejected(() ->
        requests.findWaitingOrUnconfirmedProposal(
            RequestTarget.of(TargetKind.PAYMENT, UUID.randomUUID())));
    assertRejected(() -> requests.findByStatusIn(RequestKind.DEFECT_CONFIRM, repair, Set.of()));
  }

  /**
   * 저장소 프록시는 구현이 던진 {@code IllegalArgumentException}을 Spring의 {@link
   * InvalidDataAccessApiUsageException}으로 바꿔 던진다(원래 예외는 원인으로 남음). 부르는 쪽이 실제로 받는 예외로 본다.
   */
  private static void assertRejected(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private ResponseRequest save(UUID room, RequestKind kind, RequestTarget target) {
    return requests.saveAndFlush(new ResponseRequest(room, kind, target, PartySide.TENANT, NOW));
  }

  /** 엔티티에 상태 전이가 아직 없어 테스트 준비로만 바꾼다(V1 CHECK를 지키는 상태만). */
  private void setStatus(ResponseRequest request, String status) {
    jdbc.update(
        "UPDATE state.response_request SET status = ? WHERE id = ?", status, request.getId());
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

  private static RequestTarget repairCase(UUID id) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, id);
  }

  private static RequestTarget inspectionOf(UUID id) {
    return RequestTarget.of(TargetKind.INSPECTION, id);
  }
}
