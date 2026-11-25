package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 점검 항목의 규칙(03 H "항목"·"빠뜨린 하자 더하기"). 순수 함수다.
 *
 * <p>지금은 작성 중 기준 측의 항목 더하기만 있다(INSP-1a). 상대가 더하기·잠긴 뒤 추가(INSP-1d·1e), 응답·보충은 그 기능을 만들 때 더하고, 그 전까지는
 * {@code INVALID_STATE}다.
 */
@Component
public class InspectionItemTransitions implements Transitions<InspectionItemState> {

  @Override
  public InspectionItemState initial() {
    return InspectionItemState.NONE;
  }

  @Override
  public InspectionItemState apply(InspectionItemState now, ActionEntry action) {
    return switch (action.type()) {
      case ADD_ITEM -> add(now, action);
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

  /**
   * 항목 더하기: 사용자가 적은 하자 하나(부위 + 설명 + 사진, 03 H "항목"). 확인 기다림에서 시작하고 더한 측이 기록한 측이다. 작성 중에는 기준 측만
   * 더한다(상대는 아직 "작성 중"만 보인다, 06 INSP-1a). 점검 상태를 모르면(재계산) 그때 서버가 확인했다고 본다.
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
        now.inspectionStatus(), now.inspectionAuthor(), AckState.PENDING, action.actorSide(), 1);
  }
}
