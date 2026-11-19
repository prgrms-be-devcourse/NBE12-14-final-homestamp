package com.chilsuitjo.back.correction.rule;

/**
 * 고칠 수 있는 기록 종류(06 4-6 {@code POST /api/corrections} 대상). {@code MOVE_IN_DATE}면 targetId는 호실 기록 ID.
 */
public enum CorrectionTargetKind {
  REPAIR_CASE,
  PAYMENT,
  INSPECTION_ITEM,
  SETTLEMENT_TRANSFER,
  MOVE_IN_DATE
}
