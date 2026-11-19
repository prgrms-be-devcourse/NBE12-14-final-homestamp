package com.chilsuitjo.back.notification.rule;

/**
 * 응답 요청 상태. 값은 05 ERD {@code response_request.status}(V1 CHECK)와 같다.
 *
 * <p>열린 요청은 {@link #WAITING}과 {@link #UNCONFIRMED}다. 미확인이어도 정산 완료 전까지는 늦게 답할 수 있다(03 1-4 "72시간 뒤").
 */
public enum RequestStatus {
  WAITING,
  ANSWERED,
  UNCONFIRMED,
  WITHDRAWN,
  CLOSED;

  /** 답을 받으면 "답함"으로 닫히는 상태. */
  public boolean isOpen() {
    return this == WAITING || this == UNCONFIRMED;
  }
}
