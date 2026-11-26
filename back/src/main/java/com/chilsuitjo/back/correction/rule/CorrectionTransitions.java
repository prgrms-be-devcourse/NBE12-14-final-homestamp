package com.chilsuitjo.back.correction.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 고치기 규칙(03 G, 06 REP-7). 순수 함수다: 상태는 {@code CorrectionStateStore.load}가 잠금 뒤에 읽어 {@link
 * CorrectionState}로 넘긴다.
 *
 * <ul>
 *   <li>고치기({@code CORRECT}): 원래 기록한 측 → 처리 완료(양측)면 {@code CLOSED_BY_BOTH}(REP-7k) → 그사이 도장이 바뀌었으면
 *       {@code VERSION_CONFLICT} → 같은 값이면 {@code SAME_VALUE} → 도장 뒤인데 사유가 없으면 {@code
 *       REASON_REQUIRED}
 *   <li>응답({@code RESPOND_CORRECTION}): 고친 측의 반대 측 → 같은 칸을 다시 고쳐 대신된 내용이면 {@code
 *       VERSION_CONFLICT}(REP-7j) → 처리 완료로 닫혔으면 {@code CLOSED_BY_BOTH}(REP-7l) → 거뒀거나 그 밖에 닫혔으면
 *       {@code INVALID_STATE} → 그 뒤는 {@code AnswerSupport.answer}(본 번호, 맞아요는 끝, 보류·이견에서는 맞아요로만, 사유)
 *   <li>거두기({@code WITHDRAW_CORRECTION}): 고친 측 → 기다리는 고친 내용만({@code INVALID_STATE})
 * </ul>
 */
@Component
public class CorrectionTransitions implements Transitions<CorrectionState> {

  @Override
  public CorrectionState initial() {
    return CorrectionState.forCorrecting(null, false, false, false, false);
  }

  @Override
  public CorrectionState apply(CorrectionState now, ActionEntry action) {
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return switch (action.type()) {
      case CORRECT -> correct(now, action);
      case RESPOND_CORRECTION -> respond(now, action);
      case WITHDRAW_CORRECTION -> withdraw(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  private static CorrectionState correct(CorrectionState now, ActionEntry action) {
    if (action.actorSide() != now.owner()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    if (now.closedByBoth()) {
      throw new BusinessException(ErrorCode.CLOSED_BY_BOTH);
    }
    if (now.stampChanged()) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    if (now.sameValue()) {
      throw new BusinessException(ErrorCode.SAME_VALUE);
    }
    if (now.stamped() && (action.reason() == null || action.reason().isBlank())) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    return now;
  }

  private static CorrectionState respond(CorrectionState now, ActionEntry action) {
    if (action.actorSide() == now.owner()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    switch (now.status()) {
      case SUPERSEDED -> throw new BusinessException(ErrorCode.VERSION_CONFLICT);
      case CLOSED ->
          throw new BusinessException(
              now.closedByBoth() ? ErrorCode.CLOSED_BY_BOTH : ErrorCode.INVALID_STATE);
      case WITHDRAWN -> throw new BusinessException(ErrorCode.INVALID_STATE);
      default -> {}
    }
    AckState next =
        AnswerSupport.answer(
            now.answerable(),
            action.actorSide(),
            action.payloadAs(AnswerPayload.class).answer(),
            action.reason(),
            action.seenVersionNo());
    return now.withAnswer(next);
  }

  private static CorrectionState withdraw(CorrectionState now, ActionEntry action) {
    if (action.actorSide() != now.owner()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    if (now.status() != CorrectionStatus.WAITING) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now;
  }
}
