package com.chilsuitjo.back.inspection.rule;

/** 점검 상태(03 H "작성 중 → 확인 요청 → 잠김", {@code inspection.status}). */
public enum InspectionStatus {
  /** 작성 중. 상대에게는 "작성 중"만 보인다(06 INSP-1a). */
  DRAFT,
  /** 확인 요청. 항목이 상대에게 보인다. */
  REQUESTED,
  /** 잠김. */
  LOCKED
}
