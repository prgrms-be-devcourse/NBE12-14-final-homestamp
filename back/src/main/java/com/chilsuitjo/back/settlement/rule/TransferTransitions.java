package com.chilsuitjo.back.settlement.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 정산 송금의 규칙(03 J, 06 SET-4). 순수 함수다. 응답은 공통 규칙({@code AnswerSupport})을 그대로 쓴다.
 *
 * <p>정산 완료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3).
 *
 * <p><b>현재 구현 범위의 한계</b>: 추가 송금을 "합의된 항목으로 계산한 금액이 0보다 작을 때"로 막지 않는다(계산 금액은 정산 항목을 만들 때 생김).
 */
@Component
public class TransferTransitions implements Transitions<TransferState> {

  /** 송금 상태를 바꾸는 행동. */
  public static final Set<ActionType> CHANGES =
      Set.of(ActionType.RECORD_TRANSFER, ActionType.RESPOND_TRANSFER);

  /** 송금 기록 행동의 payload(06 4-8 {@code transfers}). 금액·날짜는 판에 있고 지문으로 남는다. */
  public record RecordPayload(TransferDirection direction, UUID sameAsTransferId) {}

  @Override
  public TransferState initial() {
    return TransferState.NONE;
  }

  @Override
  public TransferState apply(TransferState now, ActionEntry action) {
    return switch (action.type()) {
      case RECORD_TRANSFER -> record(now, action);
      case RESPOND_TRANSFER -> respond(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /** 이 측이 지금 이 송금에 답할 수 있나(상세의 {@code availableActions}). {@link #respond}가 맞아요로 성공하는지와 같다. */
  public static boolean canRespond(TransferState state, PartySide side) {
    return state.exists() && AnswerSupport.canAnswer(state.answerable(), side);
  }

  /**
   * 송금 기록(03 J). 보내는 측이 남기면 보낸 기록, 받는 측이 남기면 [받았어요(혼자)]다. 같은 송금으로 묶기({@code sameAsTransferId})는 받는
   * 측만, 같은 정산·같은 방향의 보낸 쪽 기록에만 된다(SET-4e). 아니면 {@code VALIDATION_FAILED}다.
   */
  private static TransferState record(TransferState now, ActionEntry action) {
    if (now.exists()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    RecordPayload in = action.payloadAs(RecordPayload.class);
    if (in.direction() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    PartySide sender = TransferState.sender(in.direction());
    boolean bySender = action.actorSide() == sender;
    if (in.sameAsTransferId() != null && (bySender || Boolean.FALSE.equals(now.sameAsOk()))) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new TransferState(in.direction(), action.actorSide(), AckState.PENDING, 1, null);
  }

  /**
   * 응답(06 4-8 {@code respond}): 기록을 남기지 않은 측만, 본 판 번호가 지금 판과 같을 때. 그 뒤는 공통 순서다({@code
   * AnswerSupport.answer}: 상대 측 → 판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만 → 사유 필수).
   */
  private static TransferState respond(TransferState now, ActionEntry action) {
    if (!now.exists()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    AnswerPayload in = action.payloadAs(AnswerPayload.class);
    AckState next =
        AnswerSupport.answer(
            now.answerable(),
            action.actorSide(),
            in.answer(),
            action.reason(),
            action.seenVersionNo());
    return now.withResponse(next);
  }
}
