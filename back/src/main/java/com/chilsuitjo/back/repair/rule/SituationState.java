package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;

/**
 * 상황 변화 응답({@code RESPOND_SITUATION}, 06 REP-6d)이 보는 진행 상황 하나의 지금 상태. 수리 진행·하자 인정과 따로 움직여서({@code
 * RepairState}와 섞지 않음) 응답에 필요한 값만 담는다.
 *
 * @param exists 그 진행 상황이 있고 행동하는 사람에게 보이는지
 * @param noteSide 진행 상황을 남긴 측. 그 반대 측이 답한다
 * @param asksCheck [상황이 바뀌었어요]로 확인을 요청한 진행 상황인지. 아니면 답할 것이 없다
 * @param answer 지금 답({@code progress_note_response}). 답이 없으면 {@code PENDING}
 */
public record SituationState(
    boolean exists, PartySide noteSide, boolean asksCheck, AckState answer) {

  /** 대상이 없는 상태(재계산의 시작점). */
  public static final SituationState NONE = new SituationState(false, null, false, null);

  /** {@code AnswerSupport}가 보는 모양. 진행 상황은 고칠 수 없어 판 번호가 없고, 다시 요청은 새 진행 상황이다. */
  public Answerable answerable() {
    return new Answerable(noteSide, answer, null, null);
  }

  SituationState withAnswer(AckState next) {
    return new SituationState(exists, noteSide, asksCheck, next);
  }
}
