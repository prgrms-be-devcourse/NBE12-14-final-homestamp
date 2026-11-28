package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.RequestTargetColumns;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ResponseRequestLookup}의 구현(Spring Data 사용자 정의 조각, 이름은 인터페이스 + {@code Impl}). 대상 칸 비교는 V1
 * 유니크와 같은 뜻이 되게 한다.
 *
 * <ul>
 *   <li>보통 대상: 여덟 칸을 모두 {@code is not distinct from}으로 비교한다. 채운 칸은 그 ID와 같고, 나머지는 NULL이어야 맞는다(V1
 *       {@code NULLS NOT DISTINCT}와 같음). {@code =}로 비교하면 NULL 파라미터는 어느 행과도 맞지 않는다
 *   <li>호실 기록 대상: 여덟 칸이 모두 NULL이고 호실이 그 ID인 행. NULL 파라미터를 "모든 대상"으로 쓰지 않는다
 * </ul>
 */
class ResponseRequestLookupImpl implements ResponseRequestLookup {

  private static final Set<RequestKind> OWN_UNIQUE =
      EnumSet.of(RequestKind.PROPOSAL, RequestKind.INSPECTION_CONFIRM);
  private static final Set<TargetKind> AGENDA =
      EnumSet.of(TargetKind.REPAIR_CASE, TargetKind.SETTLEMENT_ITEM, TargetKind.SETTLEMENT);

  private static final String CHILD_TARGET =
      """
      r.repairCaseId is not distinct from :repairCaseId
      and r.paymentId is not distinct from :paymentId
      and r.settlementItemId is not distinct from :settlementItemId
      and r.settlementId is not distinct from :settlementId
      and r.inspectionId is not distinct from :inspectionId
      and r.inspectionItemId is not distinct from :inspectionItemId
      and r.settlementTransferId is not distinct from :settlementTransferId
      and r.progressNoteId is not distinct from :progressNoteId
      """;

  private static final String ROOM_TARGET =
      """
      r.roomRecordId = :roomRecordId
      and r.repairCaseId is null and r.paymentId is null and r.settlementItemId is null
      and r.settlementId is null and r.inspectionId is null and r.inspectionItemId is null
      and r.settlementTransferId is null and r.progressNoteId is null
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<ResponseRequest> findWaiting(RequestKind kind, RequestTarget target) {
    if (OWN_UNIQUE.contains(kind)) {
      throw new IllegalArgumentException(kind + "는 일반 요청 유니크 밖이라 전용 조회를 써요");
    }
    return single(byTarget(kind, target, EnumSet.of(RequestStatus.WAITING)).getResultList());
  }

  @Override
  public Optional<ResponseRequest> findWaitingOrUnconfirmedProposal(RequestTarget agenda) {
    if (!AGENDA.contains(agenda.kind())) {
      throw new IllegalArgumentException("제안 요청의 안건은 수리 건·정산 항목·정산이에요: " + agenda.kind());
    }
    // V1 인덱스 키와 같은 식(COALESCE)으로 찾는다. 세 칸 중 하나만 차 있으니 그 값이 안건 ID다
    List<ResponseRequest> found =
        entityManager
            .createQuery(
                """
                select r from ResponseRequest r
                where r.kind = :kind and r.status in :statuses
                  and coalesce(r.repairCaseId, r.settlementItemId, r.settlementId) = :agenda
                """,
                ResponseRequest.class)
            .setParameter("kind", RequestKind.PROPOSAL)
            .setParameter("statuses", EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED))
            .setParameter("agenda", agenda.id())
            .getResultList();
    return single(found);
  }

  @Override
  public Optional<ResponseRequest> findWaitingInspectionConfirm(
      UUID inspectionId, PartySide requestedBySide) {
    List<ResponseRequest> found =
        entityManager
            .createQuery(
                """
                select r from ResponseRequest r
                where r.kind = :kind and r.status = :status
                  and r.inspectionId = :inspectionId and r.requestedBySide = :side
                """,
                ResponseRequest.class)
            .setParameter("kind", RequestKind.INSPECTION_CONFIRM)
            .setParameter("status", RequestStatus.WAITING)
            .setParameter("inspectionId", Objects.requireNonNull(inspectionId))
            .setParameter("side", Objects.requireNonNull(requestedBySide))
            .getResultList();
    return single(found);
  }

  @Override
  public List<ResponseRequest> findByStatusIn(
      RequestKind kind, RequestTarget target, Set<RequestStatus> statuses) {
    if (statuses.isEmpty()) {
      throw new IllegalArgumentException("찾을 상태가 비어 있어요");
    }
    return byTarget(kind, target, statuses).getResultList();
  }

  @Override
  public List<ResponseRequest> findOpenRequestedBy(UUID roomRecordId, PartySide requestedBySide) {
    return entityManager
        .createQuery(
            """
            select r from ResponseRequest r
            where r.roomRecordId = :roomRecordId and r.requestedBySide = :side
              and r.status in :statuses
            order by r.createdAt, r.id
            """,
            ResponseRequest.class)
        .setParameter("roomRecordId", Objects.requireNonNull(roomRecordId))
        .setParameter("side", Objects.requireNonNull(requestedBySide))
        .setParameter("statuses", EnumSet.of(RequestStatus.WAITING, RequestStatus.UNCONFIRMED))
        .getResultList();
  }

  @Override
  public List<ResponseRequest> findRequestedBy(UUID roomRecordId, PartySide requestedBySide) {
    return entityManager
        .createQuery(
            """
            select r from ResponseRequest r
            where r.roomRecordId = :roomRecordId and r.requestedBySide = :side
            order by r.createdAt, r.id
            """,
            ResponseRequest.class)
        .setParameter("roomRecordId", Objects.requireNonNull(roomRecordId))
        .setParameter("side", Objects.requireNonNull(requestedBySide))
        .getResultList();
  }

  @Override
  public List<ResponseRequest> findInRoom(
      UUID roomRecordId, Set<RequestKind> kinds, Set<RequestStatus> statuses) {
    if (kinds.isEmpty() || statuses.isEmpty()) {
      throw new IllegalArgumentException("종류와 상태는 비울 수 없어요");
    }
    return entityManager
        .createQuery(
            """
            select r from ResponseRequest r
            where r.roomRecordId = :roomRecordId and r.kind in :kinds and r.status in :statuses
            order by r.createdAt, r.id
            """,
            ResponseRequest.class)
        .setParameter("roomRecordId", Objects.requireNonNull(roomRecordId))
        .setParameter("kinds", kinds)
        .setParameter("statuses", statuses)
        .getResultList();
  }

  @Override
  public Optional<ResponseRequest> findLatest(RequestKind kind, RequestTarget target) {
    if (kind == RequestKind.INSPECTION_CONFIRM) {
      throw new IllegalArgumentException("점검 확인은 방향이 둘이라 가장 최근 하나가 정해지지 않아요");
    }
    return byTarget(kind, target, EnumSet.allOf(RequestStatus.class), "desc")
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }

  private TypedQuery<ResponseRequest> byTarget(
      RequestKind kind, RequestTarget target, Set<RequestStatus> statuses) {
    return byTarget(kind, target, statuses, "asc");
  }

  /** {@code direction}은 만든 순서({@code createdAt}, 같으면 ID)의 방향: {@code asc} 또는 {@code desc}. */
  private TypedQuery<ResponseRequest> byTarget(
      RequestKind kind, RequestTarget target, Set<RequestStatus> statuses, String direction) {
    RequestTargetColumns columns = RequestTargetColumns.of(target);
    String where = columns.roomRecordTarget() ? ROOM_TARGET : CHILD_TARGET;
    TypedQuery<ResponseRequest> query =
        entityManager
            .createQuery(
                "select r from ResponseRequest r where r.kind = :kind and r.status in :statuses and "
                    + where
                    + " order by r.createdAt "
                    + direction
                    + ", r.id "
                    + direction,
                ResponseRequest.class)
            .setParameter("kind", kind)
            .setParameter("statuses", statuses);
    if (columns.roomRecordTarget()) {
      return query.setParameter("roomRecordId", target.id());
    }
    return query
        .setParameter("repairCaseId", columns.repairCaseId())
        .setParameter("paymentId", columns.paymentId())
        .setParameter("settlementItemId", columns.settlementItemId())
        .setParameter("settlementId", columns.settlementId())
        .setParameter("inspectionId", columns.inspectionId())
        .setParameter("inspectionItemId", columns.inspectionItemId())
        .setParameter("settlementTransferId", columns.settlementTransferId())
        .setParameter("progressNoteId", columns.progressNoteId());
  }

  /** V1 유니크가 한 건을 보장하는 조회. 둘 이상이면 제약이 깨진 것이라 조용히 하나를 고르지 않는다. */
  private static Optional<ResponseRequest> single(List<ResponseRequest> found) {
    if (found.size() > 1) {
      throw new IllegalStateException("유니크가 보장하는 조회에서 요청이 " + found.size() + "건 나왔어요");
    }
    return found.stream().findFirst();
  }
}
