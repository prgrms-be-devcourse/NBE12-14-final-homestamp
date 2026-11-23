package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 호실 기록 상태 전이(03 A). 지금은 호실 기록 만들기({@code CREATE_ROOM})만 받는다. 거주 종료·정산 완료 같은 다른 호실 행동은 그 기능을 만들 때
 * 더한다.
 */
@Component
public class RoomTransitions implements Transitions<RoomState> {

  @Override
  public RoomState initial() {
    return new RoomState(null);
  }

  @Override
  public RoomState apply(RoomState now, ActionEntry action) {
    return switch (action.type()) {
      case CREATE_ROOM -> {
        if (now.status() != null) {
          throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        yield new RoomState(RoomStatus.LIVING); // 03 A: [*] → 거주 중
      }
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }
}
