package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 원인 설명의 규칙(06 REP-8, 03 D-2 "원인에 대한 설명"). 서비스는 원인이 맞는지 판정하지 않고 "누가 무엇을 근거로 말했나"만 남긴다.
 *
 * <ul>
 *   <li>남기기·고치기({@code NOTE_CAUSE}): 측마다 지금 설명은 하나다. 고치면 언제나 새 설명을 덧붙이고, 요청에는 고칠 설명({@code
 *       previousId})을 담는다. 처음 남기면 비운다. 지금 설명과 다르면 {@code VERSION_CONFLICT}다(REP-8d: 같은 이전 설명의 수정본은
 *       하나뿐, 이미 고쳐진 설명에서 다시 고치거나 설명이 있는데 새로 남기면 최신 설명을 다시 보게 한다). 근거 종류가 없으면 {@code
 *       BASIS_REQUIRED}는 서비스가 pipeline 전에 본다(REP-8a).
 *   <li>응답({@code RESPOND_CAUSE}): 설명을 남긴 측의 상대만, 지금 설명에만 답한다. 이미 고쳐진 설명이면 {@code
 *       VERSION_CONFLICT}이고, 그 밖의 순서는 {@code AnswerSupport.answer}다(맞아요는 끝, 보류·이견에서는 맞아요로만, 사유 필수).
 *       응답 필요가 아니라 문자·기다림 타이머가 없고, 부담 합의·하자 인정을 바꾸지 않는다(REP-8b).
 * </ul>
 */
@Component
public class CauseNoteTransitions implements Transitions<CauseNoteState> {

  @Override
  public CauseNoteState initial() {
    return CauseNoteState.NONE;
  }

  @Override
  public CauseNoteState apply(CauseNoteState now, ActionEntry action) {
    return switch (action.type()) {
      case NOTE_CAUSE -> note(now, action);
      case RESPOND_CAUSE -> respond(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /** 응답 버튼을 켤지({@code canRespond}). {@link #apply}가 맞아요로 성공하는지와 같다. */
  public boolean canRespond(CauseNoteState state, PartySide side) {
    return state.exists()
        && !state.superseded()
        && AnswerSupport.canAnswer(state.answerable(), side);
  }

  private static CauseNoteState note(CauseNoteState now, ActionEntry action) {
    requireSide(action);
    if (!now.exists()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    UUID previousId = action.payloadAs(NotePayload.class).previousId();
    if (!Objects.equals(previousId, now.latestOwnNoteId())) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    return now;
  }

  private static CauseNoteState respond(CauseNoteState now, ActionEntry action) {
    requireSide(action);
    if (!now.exists()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (action.actorSide() == now.noteSide()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
    if (now.superseded()) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
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

  /** 측이 있는 주체(회원)만. 서버·운영 주체는 원인 설명을 남기지 않는다. */
  private static void requireSide(ActionEntry action) {
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
  }

  /**
   * 남기기·고치기 행동의 payload. 글·근거 종류는 설명 행에만 두고(05 2-3 "payload는 작은 부가 값만") 고칠 설명만 담는다.
   *
   * @param previousId 고칠 설명. 처음 남기면 null(키 없음)
   */
  public record NotePayload(UUID previousId) {

    /** payload의 키. record 칸 이름과 같다({@code ActionEntry.payloadAs}). */
    public static final String PREVIOUS_ID = "previousId";
  }
}
