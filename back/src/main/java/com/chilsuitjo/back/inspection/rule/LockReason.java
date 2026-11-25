package com.chilsuitjo.back.inspection.rule;

/** 잠긴 이유({@code inspection.lock_reason}). 잠금은 후속 작업에서 만든다. */
public enum LockReason {
  /** 모든 항목에 쓰지 않은 쪽의 답 또는 미확인이 붙음(06 INSP-1b). */
  ALL_RESOLVED,
  /** 항목 없이 "하자 기록 없음"으로 잠김(06 INSP-1c). */
  NO_ITEMS
}
