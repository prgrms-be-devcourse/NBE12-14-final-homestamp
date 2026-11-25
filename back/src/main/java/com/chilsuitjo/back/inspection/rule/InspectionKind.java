package com.chilsuitjo.back.inspection.rule;

/** 점검 종류({@code inspection.kind}, 05 2-6). 호실 기록마다 종류별로 하나다({@code uq_inspection_kind}). */
public enum InspectionKind {
  /** 입주 점검(06 INSP-1·2). */
  MOVE_IN,
  /** 퇴거 점검(06 INSP-3). */
  MOVE_OUT
}
