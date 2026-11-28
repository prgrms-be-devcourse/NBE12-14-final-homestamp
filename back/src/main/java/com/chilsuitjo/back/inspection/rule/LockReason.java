package com.chilsuitjo.back.inspection.rule;

/**
 * 잠긴 이유({@code inspection.lock_reason}). 항목 응답으로 잠기는 판단은 {@link InspectionLock}이고, 혼자 잠그기는 {@code
 * InspectionStateStore}가 {@code WaitService}의 혼자 진행 판단으로 정한다(#392).
 */
public enum LockReason {
  /** 모든 항목에 쓰지 않은 쪽의 답 또는 미확인이 붙음(06 INSP-1b). 72시간 미확인으로 자동 잠긴 것도 이 값이다. */
  ALL_RESOLVED,
  /** 항목 없이 "하자 기록 없음"으로 잠김(06 INSP-1c). */
  NO_ITEMS,
  /**
   * [혼자 잠그기]로 잠김: "단독 기록"(06 INSP-1g, V15). 항목이 없어도 이 값이다. 구체적인 혼자 진행 이유는 그 {@code LOCK} 행동
   * payload의 {@code solo_reason}에 있다.
   */
  SOLO
}
