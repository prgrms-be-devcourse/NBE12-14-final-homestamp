package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 응답 요청 조회(#117 5단계). 요청은 <b>대상 + 종류</b>로 찾는다(decisions 2026-11-19). 대상 ID는 복합 FK로 한 호실에만 속하고, 호실
 * 기록 대상은 그 ID가 곧 호실이라 호실을 따로 넘기지 않아도 다른 호실의 요청을 찾지 않는다.
 *
 * <p>단일 결과({@link Optional})는 V1 유니크가 "많아야 한 건"을 보장하는 경우에만 둔다. 종류마다 유니크가 달라서 메서드를 나눴다. 잠그지 않는다: 요청을
 * 바꾸는 쪽은 pipeline이 호실 행을 먼저 잠근 트랜잭션 안에서 부르고(09 3-1), 그 밖의 경로는 아직 계약이 없다.
 *
 * <p>조회는 보는 사람의 접근 권한(03 3-1 보이는 범위)을 검사하지 않는다. 찾은 요청을 사람에게 보여 주거나 그 사람의 행동으로 다루기 전에 부르는 쪽이 권한을 따로
 * 확인한다.
 */
public interface ResponseRequestLookup {

  /**
   * 일반 종류의 기다리는 요청(V1 {@code ux_response_request_waiting}: 같은 호실·종류·대상의 WAITING은 하나, 대상 칸이 모두 비어도
   * 같음). PROPOSAL·INSPECTION_CONFIRM은 그 유니크 밖이라 거절한다(각자의 메서드를 쓴다).
   */
  Optional<ResponseRequest> findWaiting(RequestKind kind, RequestTarget target);

  /**
   * 안건의 기다리는 제안 요청(V1 {@code ux_proposal_waiting}: 안건마다 WAITING·UNCONFIRMED 합쳐 하나, decisions
   * 2026-11-13 "미확인 제안도 기다리는 안"). 안건은 수리 건·정산 항목·정산(보증금)뿐이다. 그 밖의 대상은 유니크가 걸리지 않아 거절한다.
   */
  Optional<ResponseRequest> findWaitingOrUnconfirmedProposal(RequestTarget agenda);

  /** 한 점검에서 한 측이 보낸 기다리는 점검 확인(V1 {@code ux_inspection_confirm_waiting}: 점검 × 요청한 측마다 하나). */
  Optional<ResponseRequest> findWaitingInspectionConfirm(
      UUID inspectionId, PartySide requestedBySide);

  /**
   * 대상 + 종류의 요청 중 상태가 {@code statuses}에 드는 것 모두. 유니크가 한 건을 보장하지 않으므로 목록이다(예: 일반 종류의 UNCONFIRMED와
   * WAITING은 함께 있을 수 있음, 점검 확인은 두 방향). 어떤 상태를 "열림"으로 볼지는 부르는 쪽이 정한다(닫는 범위는 #113 등에서 미정). 빈 집합은
   * 거절한다.
   */
  List<ResponseRequest> findByStatusIn(
      RequestKind kind, RequestTarget target, Set<RequestStatus> statuses);

  /** 호실에서 이 측이 보낸 열린 요청(WAITING·UNCONFIRMED) 전부. 다른 호실의 요청은 들어가지 않는다(만든 순서). */
  List<ResponseRequest> findOpenRequestedBy(UUID roomRecordId, PartySide requestedBySide);

  /** 호실에서 이 측이 보낸 요청 전부(상태 무관). 다른 호실의 요청은 들어가지 않는다(만든 순서, 같으면 ID). */
  List<ResponseRequest> findRequestedBy(UUID roomRecordId, PartySide requestedBySide);

  /** 대상 + 종류의 요청 중 가장 최근에 만든 것(상태 무관). 같은 시각이면 ID가 큰 것(UUIDv7). 방향이 둘인 점검 확인은 하나로 정해지지 않아 거절한다. */
  Optional<ResponseRequest> findLatest(RequestKind kind, RequestTarget target);
}
