package com.chilsuitjo.back.notification.rule;

/**
 * 호실 단계. 어떤 확인 요청을 열 수 있는지가 이 단계에 따라 달라진다(03 K "거주 종료 뒤"). room의 {@code RoomStatus}를 notification이
 * import하지 않고 이 값으로 받는다(09 3장).
 */
public enum RoomPhase {
  /** 거주 중. */
  LIVING,
  /** 거주 종료 뒤, 정산 완료 전. */
  ENDED,
  /** 정산 완료(자동 마감 포함). */
  SETTLED
}
