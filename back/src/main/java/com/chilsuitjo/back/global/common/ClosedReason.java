package com.chilsuitjo.back.global.common;

/** 기다리던 요청이 답 없이 닫힌 이유. 값과 화면 말은 05 ERD와 같다. */
public enum ClosedReason {
  SHARING_STOPPED,
  COUNTERPART_WITHDRAWN,
  SETTLED,
  CLOSED_BY_BOTH,
  SETTLEMENT_CHANGED,
  AMOUNT_CHANGED,
  ROOM_ENDED
}
