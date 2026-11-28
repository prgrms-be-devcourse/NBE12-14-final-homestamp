package com.chilsuitjo.back.payment.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.LocalDate;
import java.util.List;

/**
 * 지불 행동({@code RECORD_PAYMENT}·{@code RESPOND_AMOUNT}, 06 MONEY-1·2)이 보는 지금 상태. 행동마다 필요한 값만 담는다.
 *
 * @param paidOn 기록: 새 지불의 낸 날짜. 비슷한 기록(MONEY-1c)을 이 날짜와 비교한다
 * @param similar 기록: 같은 수리 건·방향·금액의 지불 중 행동하는 사람에게 보이는 것(기록 순서). 보이지 않는 기록은 store가 미리 뺀다
 * @param recordedBy 응답: 지금 판을 기록한 측. 그 반대 측이 답한다
 * @param response 응답: 지금 응답
 * @param versionNo 응답: 지금 판 번호. 본 판 번호와 같아야 답할 수 있다
 */
public record PaymentState(
    LocalDate paidOn,
    List<SimilarPayment> similar,
    PartySide recordedBy,
    PaymentResponse response,
    Integer versionNo) {

  /** 대상이 없는 상태. */
  public static final PaymentState NONE = new PaymentState(null, List.of(), null, null, null);

  public PaymentState {
    similar = List.copyOf(similar);
  }

  /** 기록할 때의 지금 상태. */
  public static PaymentState forRecording(LocalDate paidOn, List<SimilarPayment> similar) {
    return new PaymentState(paidOn, similar, null, null, null);
  }

  /** 응답할 때의 지금 상태. */
  public static PaymentState forResponding(
      PartySide recordedBy, PaymentResponse response, int versionNo) {
    return new PaymentState(null, List.of(), recordedBy, response, versionNo);
  }

  /**
   * {@code AnswerSupport}가 보는 모양. 보충하고 다시 요청({@code SUPPLEMENT_PAYMENT})은 아직 없어서 마지막 다시 요청 시각은 비운다.
   * 보충을 만들 때 채운다.
   */
  public Answerable answerable() {
    return new Answerable(recordedBy, AckState.valueOf(response.name()), versionNo, null);
  }

  PaymentState withResponse(PaymentResponse next) {
    return new PaymentState(paidOn, similar, recordedBy, next, versionNo);
  }
}
