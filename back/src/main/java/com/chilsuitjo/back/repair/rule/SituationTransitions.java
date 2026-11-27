package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 상황 변화 응답의 규칙(06 REP-6d, 03 G "상황이 바뀌면": 상대는 같은 두 버튼으로 답한다). [상황이 바뀌었어요]로 남긴 진행 상황에 그 상대 측만 답하고,
 * 순서는 {@code AnswerSupport.answer}다(맞아요는 끝 {@code ACK_FINAL}, 보류·이견에서는 맞아요로만 {@code INVALID_STATE},
 * 사유 {@code REASON_REQUIRED}). 하자 인정은 바꾸지 않는다(REP-6d "하자 [맞아요]는 그대로").
 *
 * <p>거주 종료 뒤에도 정산 완료 전까지 답할 수 있다(늦은 답, 03 G "바꿀 수 있는 기간"). 정산 완료는 pipeline이 막는다.
 */
@Component
public class SituationTransitions implements Transitions<SituationState> {

  @Override
  public SituationState initial() {
    return SituationState.NONE;
  }

  @Override
  public SituationState apply(SituationState now, ActionEntry action) {
    if (action.type() != ActionType.RESPOND_SITUATION) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return respond(now, action);
  }

  /**
   * 응답 버튼을 켤지({@code canRespond}, 수리 건 상세의 진행 상황). {@link #apply}가 맞아요로 성공하는지와 같다: [상황이 바뀌었어요]로 남긴
   * 진행 상황에 그 상대 측이, 아직 맞아요가 아닐 때.
   */
  public boolean canRespond(SituationState state, PartySide side) {
    return state.exists()
        && state.asksCheck()
        && side != null
        && AnswerSupport.canAnswer(state.answerable(), side);
  }

  /**
   * 상황 변화 응답. 측이 없는 주체(서버·운영)이거나 [상황이 바뀌었어요]로 남긴 진행 상황이 아니면 {@code INVALID_STATE}다. 진행 상황은 판이 하나라 본
   * 판 번호는 보지 않는다(원인 설명과 같음).
   */
  private static SituationState respond(SituationState now, ActionEntry action) {
    if (action.actorSide() == null || !now.exists() || !now.asksCheck()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    AckState next =
        AnswerSupport.answer(
            now.answerable(),
            action.actorSide(),
            action.payloadAs(AnswerPayload.class).answer(),
            action.reason(),
            null);
    return now.withAnswer(next);
  }
}
