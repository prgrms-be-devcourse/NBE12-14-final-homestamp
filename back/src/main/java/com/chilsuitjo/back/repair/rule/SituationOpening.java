package com.chilsuitjo.back.repair.rule;

/**
 * 행동하는 측이 지금 [상황이 바뀌었어요] 확인 요청({@code SITUATION_CHECK})을 열 수 있는지(06 REP-6d). 호실 단계·공유 중단처럼 행동 기록 밖의
 * 사실이라 진행 상황을 남길 때 {@code load}가 채운다(09 3-1 "계약": 같은 호실 사실이라도 행동마다 결과가 다른 판단).
 */
public enum SituationOpening {
  /** 열 수 있다. */
  OPEN,

  /** 행동하는 사람이 공유를 그만뒀다. 진행 상황은 남기지만 상대에게 확인을 요청하지 않는다(03 B) → {@code SHARING_STOPPED}. */
  SHARING_STOPPED,

  /**
   * 지금 열 수 없다: 거주 종료 뒤처럼 호실 단계에서 열 수 없거나(03 K), 상대가 공유를 그만둬 이 진행 상황이 상대에게 보이지 않는다(03 불변식 18) →
   * {@code INVALID_STATE}.
   */
  BLOCKED
}
