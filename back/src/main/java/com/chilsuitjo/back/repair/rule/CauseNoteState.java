package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 원인 설명 행동({@code NOTE_CAUSE}·{@code RESPOND_CAUSE}, 06 REP-8)이 보는 지금 상태. 수리 진행·하자 인정과 따로
 * 움직여서({@code RepairState}와 섞지 않음, 03 D-2 "원인에 대한 설명") 행동마다 필요한 값만 담는다.
 *
 * @param exists 남기기면 행동하는 사람이 그 수리 건을 볼 수 있는지, 응답이면 그 설명이 있고 보이는지
 * @param latestOwnNoteId 남기기: 행동하는 측의 지금 설명(다음 수정본이 없는 것). 없으면 null. 고치기는 이 값을 {@code previousId}로
 *     보내야 한다(06 REP-8d)
 * @param noteSide 응답: 설명을 남긴 측. 그 반대 측이 답한다
 * @param superseded 응답: 이미 고쳐져 새 설명이 있는지. 이전 설명에는 답하지 않는다(REP-8c "이전 응답은 새 설명에 붙지 않는다")
 * @param answer 응답: 그 설명의 지금 응답. 답이 없으면 {@code PENDING}
 */
public record CauseNoteState(
    boolean exists, UUID latestOwnNoteId, PartySide noteSide, boolean superseded, AckState answer) {

  /** 대상이 없거나 볼 수 없는 상태. */
  public static final CauseNoteState NONE = new CauseNoteState(false, null, null, false, null);

  /** 남기기·고치기의 지금 상태. */
  public static CauseNoteState forNoting(UUID latestOwnNoteId) {
    return new CauseNoteState(true, latestOwnNoteId, null, false, null);
  }

  /** 응답의 지금 상태. */
  public static CauseNoteState forResponding(
      PartySide noteSide, boolean superseded, AckState answer) {
    return new CauseNoteState(true, null, noteSide, superseded, answer);
  }

  /** {@code AnswerSupport}가 보는 모양. 설명에는 판 번호가 없고(고치면 새 행) 다시 요청도 없다. */
  public Answerable answerable() {
    return new Answerable(noteSide, answer, null, null);
  }

  CauseNoteState withAnswer(AckState next) {
    return new CauseNoteState(exists, latestOwnNoteId, noteSide, superseded, next);
  }
}
