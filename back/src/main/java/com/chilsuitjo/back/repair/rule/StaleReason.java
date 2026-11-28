package com.chilsuitjo.back.repair.rule;

/** 오래 멈춘 수리 건의 이유(화면 "처리 중 · 15일째", "보충 요청됨 · 15일째", 06 REP-6a·6e). 정하는 법은 {@link StaleRule}. */
public enum StaleReason {
  /** 처리 중(06 REP-6a). 하자가 보충 요청됨이어도 처리 중이면 이 값이다. */
  IN_PROGRESS,
  /** 시작 전이면서 하자가 보충 요청됨(보류, 06 REP-6e). */
  NEED_MORE
}
