package com.chilsuitjo.back.inspection.rule;

/**
 * 잠긴 이유({@code inspection.lock_reason}). 판단은 {@link InspectionLock}이다. 혼자 잠금(상대 탈퇴 등)의 이유를 어디에 남길지는
 * #326 논의 2로 정하지 않았다.
 */
public enum LockReason {
  /** 모든 항목에 쓰지 않은 쪽의 답 또는 미확인이 붙음(06 INSP-1b). */
  ALL_RESOLVED,
  /** 항목 없이 "하자 기록 없음"으로 잠김(06 INSP-1c). */
  NO_ITEMS
}
