package com.chilsuitjo.back.repair.rule;

/** 수리 진행(03 D-2). 하자 인정과 따로 간다. 값은 V1 {@code repair_case.status} CHECK와 같다. */
public enum RepairStatus {
  NOT_STARTED,
  IN_PROGRESS,
  CLOSE_WAIT,
  DONE
}
