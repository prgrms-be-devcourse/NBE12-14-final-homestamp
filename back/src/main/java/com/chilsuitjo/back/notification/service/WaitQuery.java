package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 응답 요청 읽기 전용 조회(#266). 다른 도메인이 수리 목록·상세·홈에서 "누구의 답을 기다리는 요청이 있고 어떤 상태인가"를 보여 줄 때 쓴다. 요청을 바꾸는 일은
 * {@link WaitService}이고, 이 포트는 아무것도 바꾸지 않는다.
 *
 * <p>보는 사람이 그 요청을 볼 수 있는지(03 3-1 보이는 범위)는 검사하지 않는다. 부르는 쪽이 먼저 확인한다({@code ResponseRequestLookup}과
 * 같은 원칙). 지금 답할 수 있는지도 각 도메인의 행동 규칙이 정한다.
 */
public interface WaitQuery {

  /**
   * 호실에서 이 측이 답할 요청(요청한 측이 {@code answeringSide}의 상대) 중 열린 것({@code WAITING}·{@code UNCONFIRMED})
   * 전부. 다른 호실의 요청은 들어가지 않는다. 순서는 보장하지 않는다. 없으면 빈 목록.
   *
   * <p>호실 단위라 모든 종류·대상이 섞여 나온다. 화면에 담을 종류·대상(예: 06 4-6 수리 목록 {@code waiting}은 그 수리 건과 그 건의 진행 상황
   * 대상만, 금액 확인 제외)은 부르는 쪽이 고른다.
   */
  List<RequestView> awaiting(UUID roomRecordId, PartySide answeringSide);

  /**
   * 대상 하나·종류 하나에서 가장 최근에 만든 요청(닫힌 것 포함). 대상 ID가 한 호실의 기록이라 호실 범위는 대상으로 정해진다. 요청이 없으면 빈 값.
   *
   * @throws IllegalArgumentException {@code INSPECTION_CONFIRM}(한 점검에 두 방향이 있을 수 있어 "가장 최근 하나"가
   *     정해지지 않음)
   */
  Optional<RequestView> latest(RequestKind kind, RequestTarget target);
}
