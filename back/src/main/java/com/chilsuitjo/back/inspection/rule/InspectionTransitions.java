package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 점검의 규칙(03 H, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다. Repository·{@code Clock}·다른
 * 서비스를 부르지 않는다.
 *
 * <p>정산 완료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3).
 *
 * <p>지금은 입주 점검 시작(INSP-1a·2b)만 있다. 확인 요청·잠금(INSP-1b~f)은 그 기능을 만들 때 더하고, 그 전까지 다른 행동은 {@code
 * INVALID_STATE}다. 퇴거 점검(INSP-3)·늦은 입주 표시({@code late}, INSP-2a)는 B가 만든다(07 WBS 3주차).
 */
@Component
public class InspectionTransitions implements Transitions<InspectionState> {

  @Override
  public InspectionState initial() {
    return InspectionState.NONE;
  }

  @Override
  public InspectionState apply(InspectionState now, ActionEntry action) {
    return switch (action.type()) {
      case START_INSPECTION -> start(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 점검 시작: 먼저 시작한 측이 기준 기록을 쓰고 작성 중에서 시작한다(03 H "입주 점검 기준 기록"). 호실을 잠근 뒤 읽으므로 동시에 시작해도 먼저 저장된 쪽만
   * 기준이 되고 나머지는 {@code INSPECTION_EXISTS}다(1-3).
   *
   * <p>입주일(06 4-7, INSP-2b): 호실 입주일이 비어 있으면 함께 보내야 하고({@code MOVE_IN_DATE_REQUIRED}), 이미 있으면 보내지
   * 않는다({@code VALIDATION_FAILED}: 입주일을 바꾸는 것은 고치기, 03 H "늦은 입주 점검"). 그래서 payload의 입주일은 늘 "이 행동이 채운
   * 값"이다.
   *
   * <p>거주 종료 뒤에는 입주 점검을 새로 시작하지 않는다({@code INVALID_STATE}, 03 A "상태별로 할 수 있는 일"에 없음).
   *
   * <p><b>현재 구현 범위의 한계</b>: 퇴거 점검({@code MOVE_OUT})은 아직 만들지 않아 {@code INVALID_STATE}로 받지 않는다. 규칙이
   * 아니라 구현 전이라서다. 퇴거 점검(06 INSP-3, B 담당)을 만들 때 이 거절을 지운다.
   */
  private static InspectionState start(InspectionState now, ActionEntry action) {
    if (now.exists()) {
      throw new BusinessException(ErrorCode.INSPECTION_EXISTS);
    }
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    StartInspectionPayload in = action.payloadAs(StartInspectionPayload.class);
    if (in.kind() != InspectionKind.MOVE_IN) {
      // 현재 구현 범위의 한계: 퇴거 점검은 아직 없다(INSP-3)
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (Boolean.TRUE.equals(now.roomEnded())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    boolean dateGiven = in.moveInDate() != null;
    if (Boolean.TRUE.equals(now.moveInDateMissing()) && !dateGiven) {
      throw new BusinessException(ErrorCode.MOVE_IN_DATE_REQUIRED);
    }
    if (Boolean.FALSE.equals(now.moveInDateMissing()) && dateGiven) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new InspectionState(
        in.kind(),
        InspectionStatus.DRAFT,
        action.actorSide(),
        now.moveInDateMissing() == null ? null : false,
        now.roomEnded());
  }
}
