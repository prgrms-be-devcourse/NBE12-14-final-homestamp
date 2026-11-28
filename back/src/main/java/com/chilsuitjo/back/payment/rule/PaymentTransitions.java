package com.chilsuitjo.back.payment.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.stereotype.Component;

/**
 * 지불의 규칙(06 MONEY-1·2, 03 F).
 *
 * <ul>
 *   <li>기록({@code RECORD_PAYMENT}): 같은 수리 건·방향(낸 사람·받는 사람의 종류)·금액의 지불이 있으면 막는다. 먼저 상대가 남겼고 내가 아직
 *       맞아요를 누르지 않은 기록이 있으면 {@code similar = false}로 막는다(MONEY-1a). 기간과 상관없고 {@code
 *       similarConfirmed}로도 넘길 수 없다. 화면은 그 기록의 [확인]을 보여 준다. 그다음 낸 날짜가 {@link #SIMILAR_WINDOW} 안인
 *       기록이 있으면 {@code similar = true}로 묻는다(MONEY-1c). {@code similarConfirmed: true}면 남긴다. 둘 다 기록
 *       순서에서 처음 걸린 기록 ID를 싣는다
 *   <li>응답({@code RESPOND_AMOUNT}): 기록한 측의 상대가 지금 판에만 답한다. 순서는 {@code AnswerSupport.answer}다(상대 측 →
 *       판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만 → 사유 필수)
 * </ul>
 */
@Component
public class PaymentTransitions implements Transitions<PaymentState> {

  /**
   * 비슷한 기록으로 묻는 기간(06 MONEY-1c "30일 안"). 낸 날짜({@code paid_on})끼리 앞뒤로 30일 이하이면 비슷하다. 06 0-1에 설정이 없어서
   * 상수로 둔다.
   */
  public static final Period SIMILAR_WINDOW = Period.ofDays(30);

  @Override
  public PaymentState initial() {
    return PaymentState.NONE;
  }

  @Override
  public PaymentState apply(PaymentState now, ActionEntry action) {
    return switch (action.type()) {
      case RECORD_PAYMENT -> record(now, action);
      case RESPOND_AMOUNT -> respond(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  private static PaymentState record(PaymentState now, ActionEntry action) {
    requireSide(action);
    PartySide side = action.actorSide();
    for (SimilarPayment candidate : now.similar()) {
      if (candidate.recordedBy() != side && candidate.response() != PaymentResponse.ACK) {
        throw BusinessException.duplicatePayment(candidate.paymentId(), false);
      }
    }
    if (Boolean.TRUE.equals(action.payloadAs(RecordPayload.class).similarConfirmed())) {
      return now;
    }
    for (SimilarPayment candidate : now.similar()) {
      if (within(candidate.paidOn(), now.paidOn())) {
        throw BusinessException.duplicatePayment(candidate.paymentId(), true);
      }
    }
    return now;
  }

  private static PaymentState respond(PaymentState now, ActionEntry action) {
    requireSide(action);
    if (now.recordedBy() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    AckState next =
        AnswerSupport.answer(
            now.answerable(),
            action.actorSide(),
            action.payloadAs(AnswerPayload.class).answer(),
            action.reason(),
            action.seenVersionNo());
    return now.withResponse(PaymentResponse.valueOf(next.name()));
  }

  private static boolean within(LocalDate other, LocalDate paidOn) {
    return !other.isBefore(paidOn.minus(SIMILAR_WINDOW))
        && !other.isAfter(paidOn.plus(SIMILAR_WINDOW));
  }

  /** 측이 있는 주체(회원)만. 서버·운영 주체는 지불을 남기거나 답하지 않는다. */
  private static void requireSide(ActionEntry action) {
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
  }

  /**
   * 기록 행동의 payload. 금액·낸 사람 같은 내용은 지불 판에 두고(05 2-3 "payload는 작은 부가 값만") 안내를 보고도 남기는지만 담는다.
   *
   * @param similarConfirmed 비슷한 기록 안내를 보고도 남기는지(MONEY-1c). 아니면 null(키 없음)
   */
  public record RecordPayload(Boolean similarConfirmed) {

    /** payload의 키. record 칸 이름과 같다({@code ActionEntry.payloadAs}). */
    public static final String SIMILAR_CONFIRMED = "similarConfirmed";
  }
}
