package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 호실 부위 상태 전이(03 H "부위", 06 ROOM-3, #178). 부위는 지우지 않고 숨기기만 하고, 숨김과 켜고 끔은 따로 움직인다.
 *
 * <ul>
 *   <li>{@code ADD_PART}: 직접 더한 부위는 켬·보임으로 시작하고 켜고 끌 수 없다
 *   <li>{@code HIDE_PART}·{@code SHOW_PART}: 이미 그 상태면 {@code INVALID_STATE}
 *   <li>{@code TOGGLE_PART}: 원하는 값({@code enabled})을 받는다(뒤집기가 아님). 설비·가전이 아니면 {@code
 *       VALIDATION_FAILED}, 이미 그 값이면 {@code INVALID_STATE}
 * </ul>
 */
@Component
public class PartTransitions implements Transitions<PartState> {

  /** {@code TOGGLE_PART} 행동 payload. */
  public record TogglePayload(Boolean enabled) {}

  @Override
  public PartState initial() {
    return PartState.NONE;
  }

  @Override
  public PartState apply(PartState now, ActionEntry action) {
    return switch (action.type()) {
      case ADD_PART -> {
        if (now.exists()) {
          throw invalidState();
        }
        yield PartState.ADDED;
      }
      case HIDE_PART -> {
        if (!now.exists() || now.hidden()) {
          throw invalidState();
        }
        yield now.withHidden(true);
      }
      case SHOW_PART -> {
        if (!now.exists() || !now.hidden()) {
          throw invalidState();
        }
        yield now.withHidden(false);
      }
      case TOGGLE_PART -> {
        Boolean enabled = action.payloadAs(TogglePayload.class).enabled();
        if (!now.exists()) {
          throw invalidState();
        }
        if (enabled == null || !now.toggleable()) {
          throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (enabled == now.enabled()) {
          throw invalidState();
        }
        yield now.withEnabled(enabled);
      }
      default -> throw invalidState();
    };
  }

  private static BusinessException invalidState() {
    return new BusinessException(ErrorCode.INVALID_STATE);
  }
}
