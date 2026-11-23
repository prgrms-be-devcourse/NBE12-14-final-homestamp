package com.chilsuitjo.back.payment.rule;

/** 지불 확인 강도(05 2-5). 보류·이견은 {@link PaymentResponse}로 따로 표시한다. */
public enum ConfirmStrength {
  ONE_SIDE,
  EVIDENCE,
  BOTH
}
