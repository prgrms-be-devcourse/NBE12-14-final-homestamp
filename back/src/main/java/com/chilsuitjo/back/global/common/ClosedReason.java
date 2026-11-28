package com.chilsuitjo.back.global.common;

/** 기다리던 요청이 답 없이 닫힌 이유. 값과 화면 말은 05 ERD와 같다. */
public enum ClosedReason {
  SHARING_STOPPED,
  COUNTERPART_WITHDRAWN,
  SETTLED,
  CLOSED_BY_BOTH,
  SETTLEMENT_CHANGED,
  AMOUNT_CHANGED,
  ROOM_ENDED,
  /** 초대받은 사람이 거절해 초대한 측이 요청한 열린 요청을 닫음(#392, V16). 거절한 측이 요청한 것·이미 답한 요청은 그대로다. */
  INVITE_REJECTED
}
