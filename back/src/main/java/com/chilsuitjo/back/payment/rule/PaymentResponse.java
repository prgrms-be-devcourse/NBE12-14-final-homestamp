package com.chilsuitjo.back.payment.rule;

/** 지불 금액에 대한 상대의 지금 응답(05 2-5). 응답 전은 NONE이다. */
public enum PaymentResponse {
  NONE,
  ACK,
  NEED_MORE,
  DISPUTE
}
