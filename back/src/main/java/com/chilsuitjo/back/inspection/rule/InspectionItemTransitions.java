package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 점검 항목의 규칙(03 H "항목"·"상대의 확인", G "응답과 그 뒤"). 순수 함수다. 응답·보충은 공통 규칙({@code AnswerSupport})을 그대로 쓴다.
 *
 * <p><b>현재 구현 범위의 한계</b>: 항목 더하기는 작성 중 기준 측만 있다. 규칙으로는 누구든 점검이 있는 동안 언제든 더할 수 있고 상대가 더한 것은 기준 측이
 * 확인한다(03 H "빠뜨린 하자 더하기", 6-6 2번). 상대가 더하기·잠긴 뒤 추가(INSP-1d·1e)는 그 기능을 만들 때 더하고, 그 전까지는 {@code
 * INVALID_STATE}다.
 */
@Component
public class InspectionItemTransitions implements Transitions<InspectionItemState> {

  private final Duration rerequestInterval;

  public InspectionItemTransitions(
      @Value("${app.rerequest.interval:7d}") Duration rerequestInterval) {
    this.rerequestInterval = rerequestInterval;
  }

  @Override
  public InspectionItemState initial() {
    return InspectionItemState.NONE;
  }

  @Override
  public InspectionItemState apply(InspectionItemState now, ActionEntry action) {
    return switch (action.type()) {
      case ADD_ITEM -> add(now, action);
      case RESPOND_ITEM -> respond(now, action);
      case SUPPLEMENT_ITEM -> supplement(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 이 측이 지금 이 점검에 항목을 더할 수 있나(상세 조회의 {@code availableActions}, 06 4-0). {@link #apply}의 항목 더하기와 같은
   * 판단이다. 호실 단위 거절(정산 완료·공유 중단 등)은 여기서 보지 않는다({@code RoomGate.rejection}).
   */
  public static boolean canAdd(
      InspectionStatus inspectionStatus, PartySide author, PartySide side) {
    return inspectionStatus == InspectionStatus.DRAFT && side != null && side == author;
  }

  /** 이 측이 지금 이 항목에 답할 수 있나({@link #apply}의 응답이 맞아요로 성공하는지와 같음). 호실 단위 거절은 보지 않는다. */
  public static boolean canRespond(InspectionItemState state, PartySide side) {
    return state.exists()
        && answerable(state.inspectionStatus())
        && !Boolean.TRUE.equals(state.hiddenFromActor())
        && AnswerSupport.canAnswer(state.answerable(), side);
  }

  /** 이 측이 지금 이 항목을 보충할 수 있나(근거는 더한다고 봄, {@code AnswerSupport.canRerequest}). 호실 단위 거절은 보지 않는다. */
  public boolean canSupplement(InspectionItemState state, PartySide side, Instant now) {
    return state.exists()
        && AnswerSupport.canRerequest(
            state.answerable(),
            side,
            !Boolean.FALSE.equals(state.canOpenConfirm()),
            now,
            rerequestInterval);
  }

  /** 다음에 보충할 수 있는 시각. 보충한 적이 없으면 null(지금 바로 됨). */
  public Instant nextRerequestAt(InspectionItemState state) {
    return AnswerSupport.nextRerequestAt(state.answerable(), rerequestInterval);
  }

  /** 항목에 답할 수 있는 점검 상태: 확인 요청 뒤. 잠긴 뒤에도 정산 완료 전까지 늦게 답한다(03 H). 모르면(재계산) 서버가 확인했다고 본다. */
  static boolean answerable(InspectionStatus status) {
    return status == null
        || status == InspectionStatus.REQUESTED
        || status == InspectionStatus.LOCKED;
  }

  /**
   * 항목 더하기: 사용자가 적은 하자 하나(부위 + 설명 + 사진, 03 H "항목"). 확인 기다림에서 시작하고 더한 측이 기록한 측이다. 현재 구현 범위의 한계로 작성 중
   * 기준 측만 받는다(위 클래스 설명). 점검 상태를 모르면(재계산) 그때 서버가 확인했다고 본다.
   */
  private static InspectionItemState add(InspectionItemState now, ActionEntry action) {
    if (now.exists() || action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (now.inspectionStatus() != null
        && !canAdd(now.inspectionStatus(), now.inspectionAuthor(), action.actorSide())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return new InspectionItemState(
        now.inspectionStatus(),
        now.inspectionAuthor(),
        AckState.PENDING,
        action.actorSide(),
        1,
        null,
        null,
        null);
  }

  /**
   * 항목 응답(06 4-7 {@code respond}): 확인 요청 뒤에만(작성 중이면 {@code INVALID_STATE}), 답하는 사람에게 보이는 항목만(작성 중에
   * 가려졌거나 공유를 그만둔 뒤라 보이지 않으면 {@code INVALID_STATE}, 불변식 18). 그 뒤는 공통 순서다({@code
   * AnswerSupport.answer}: 상대 측 → 판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만 → 사유 필수). 사유는 행동 기록의 {@code
   * reason}이다.
   */
  private static InspectionItemState respond(InspectionItemState now, ActionEntry action) {
    if (!now.exists()
        || !answerable(now.inspectionStatus())
        || Boolean.TRUE.equals(now.hiddenFromActor())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    AnswerPayload in = action.payloadAs(AnswerPayload.class);
    AckState next =
        AnswerSupport.answer(
            now.answerable(),
            action.actorSide(),
            in.answer(),
            action.reason(),
            action.seenVersionNo());
    return now.withStatus(next);
  }

  /**
   * 보충하고 다시 확인 요청(06 4-7 {@code supplement}): 항목을 더한 측만, 보류·이견일 때만, 지금 점검 확인 요청을 열 수 있을 때만, 7일에 한
   * 번({@code AnswerSupport.rerequest}). 근거(사진이나 설명)는 서비스가 pipeline 전에 본다. 항목은 확인 기다림으로 돌아간다.
   */
  private InspectionItemState supplement(InspectionItemState now, ActionEntry action) {
    if (!now.exists()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    AnswerSupport.Rerequested result =
        AnswerSupport.rerequest(
            now.answerable(),
            action.actorSide(),
            true,
            !Boolean.FALSE.equals(now.canOpenConfirm()),
            action.createdAt(),
            rerequestInterval);
    return now.withStatus(result.state()).withLastRerequestAt(result.rerequestedAt());
  }
}
