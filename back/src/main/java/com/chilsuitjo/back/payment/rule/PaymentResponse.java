package com.chilsuitjo.back.payment.rule;

/**
 * 지불 금액에 대한 상대의 지금 응답(05 2-5). 응답 전은 PENDING이다.
 *
 * <p>응답 공통 규칙({@code AnswerSupport})의 {@code AckState}와 달리 {@code UNCONFIRMED}가 없다. {@code
 * AnswerSupport}는 사람이 고른 답(ACK·NEED_MORE·DISPUTE)이나 다시 요청 뒤의 PENDING만 돌려주므로 지불로 들어오지 않는다.
 */
public enum PaymentResponse {
  PENDING,
  ACK,
  NEED_MORE,
  DISPUTE
}
