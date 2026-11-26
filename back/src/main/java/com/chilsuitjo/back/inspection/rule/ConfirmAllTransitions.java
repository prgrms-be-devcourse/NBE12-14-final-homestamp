package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 남은 항목 모두 확인의 규칙(06 4-7 {@code confirm-all}, 03 1-4 "한 번에 확인"). 순수 함수다. 각 항목의 맞아요는 뒤따르는 {@code
 * RESPOND_ITEM}이 {@link InspectionItemTransitions}로 남기고, 여기서는 "본 것과 지금이 같은지"만 본다(2026-11-25 #235
 * 결정).
 *
 * <ul>
 *   <li>대상은 행동하는 사람이 답하지 않은 항목(확인 기다림·확인 없음)뿐이다. 보류·이견은 하나씩 답한다(03 H "이견은 하나씩")
 *   <li>요청 payload {@code items}의 본 항목·판 번호가 지금 대상과 하나라도 다르면 {@code VERSION_CONFLICT}(화면을 연 뒤 바뀐
 *       항목에 보지 않고 도장을 찍지 않게)
 *   <li>항목이 없는 점검이면 "하자 기록 없음" 확인이다(03 H "항목이 하나도 없어도 상대가 확인해야 잠긴다"). 기준 측이 아닌 사람만, 한 번만
 * </ul>
 */
@Component
public class ConfirmAllTransitions implements Transitions<ConfirmAllState> {

  /** payload의 본 항목 목록 키. 값은 {@code [{id, seenVersionNo}]}. */
  public static final String ITEMS = "items";

  public static final String ITEM_ID = "id";
  public static final String SEEN_VERSION_NO = "seenVersionNo";

  @Override
  public ConfirmAllState initial() {
    // 모두 확인은 점검 상태를 바꾸지 않아 다시 계산하지 않는다. 빈 값은 재계산 자리를 채울 뿐이다
    return new ConfirmAllState(null, null, Map.of(), 0, false, false);
  }

  @Override
  public ConfirmAllState apply(ConfirmAllState now, ActionEntry action) {
    if (action.type() != ActionType.CONFIRM_ALL) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (!InspectionItemTransitions.answerable(now.status()) || now.itemsHidden()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (!seen(action).equals(now.unanswered())) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    if (now.unanswered().isEmpty()) {
      requireNoItemsConfirmable(now, action.actorSide());
    }
    return now;
  }

  /** 화면에서 [남은 항목 모두 확인]을 켤지. {@link #apply}가 지금 대상을 그대로 보내면 성공하는지와 같다. 호실 단위 거절은 보지 않는다. */
  public static boolean canConfirmAll(ConfirmAllState state, PartySide side) {
    if (!InspectionItemTransitions.answerable(state.status()) || state.itemsHidden()) {
      return false;
    }
    return !state.unanswered().isEmpty()
        || (state.noItems() && side != state.author() && !state.confirmedNoItems());
  }

  private static void requireNoItemsConfirmable(ConfirmAllState now, PartySide side) {
    if (!now.noItems() || now.confirmedNoItems()) {
      // 답할 항목이 남지 않았다(모두 답했거나 보류·이견만 남음). 보류·이견은 하나씩 답한다
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (side == now.author()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
  }

  /** payload의 본 항목·판 번호. 형식이 틀리면 {@code VALIDATION_FAILED}(서비스가 먼저 보지만 규칙도 같은 값을 읽는다). */
  static Map<UUID, Integer> seen(ActionEntry action) {
    Object raw = action.payload().get(ITEMS);
    if (!(raw instanceof List<?> list)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    Map<UUID, Integer> result = new HashMap<>();
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> entry)
          || entry.get(ITEM_ID) == null
          || !(entry.get(SEEN_VERSION_NO) instanceof Number no)) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      UUID id = UUID.fromString(entry.get(ITEM_ID).toString());
      if (result.put(id, no.intValue()) != null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
    }
    return result;
  }
}
