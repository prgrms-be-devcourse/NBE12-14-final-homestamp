package com.chilsuitjo.back.settlement.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 정산의 규칙(03 I, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다. Repository·{@code Clock}·다른
 * 서비스를 부르지 않는다.
 *
 * <p>정산 완료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3). 거주 중·거주 종료 모두 열 수 있다(03 I "정산 열기").
 *
 * <p>지금은 정산 열기({@code OPEN_SETTLEMENT}, SET-1)만 있다. 송금(SET-4)·끝내기(SET-5)는 그 기능을 만들 때 더하고, 그 전까지 다른
 * 행동은 {@code INVALID_STATE}다.
 */
@Component
public class SettlementTransitions implements Transitions<SettlementState> {

  /** 정산 상태를 바꾸는 행동. 보이는 범위를 다시 계산할 때({@code Replay.fold}) 이 행동만 넣는다. */
  public static final Set<ActionType> CHANGES = Set.of(ActionType.OPEN_SETTLEMENT);

  @Override
  public SettlementState initial() {
    return SettlementState.NONE;
  }

  @Override
  public SettlementState apply(SettlementState now, ActionEntry action) {
    return switch (action.type()) {
      case OPEN_SETTLEMENT -> open(now);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /** 정산 열기(03 I). 호실마다 하나라 이미 열렸으면 {@code SETTLEMENT_EXISTS}다. */
  private static SettlementState open(SettlementState now) {
    if (now.opened()) {
      throw new BusinessException(ErrorCode.SETTLEMENT_EXISTS);
    }
    return SettlementState.OPENED;
  }
}
