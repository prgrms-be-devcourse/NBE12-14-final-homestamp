package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 수리 건의 규칙(03 D, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다. Repository·{@code Clock}·다른
 * 서비스를 부르지 않는다. 시각이 필요하면 {@code action.createdAt()}을 쓴다.
 *
 * <p>정산 완료·거주 종료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3). 거주 종료 뒤 새 수리 건은 {@code REGISTER}의
 * {@code blockedAfterEnded}로 막힌다(06 REP-1c).
 *
 * <p>지금은 하자 등록(REP-1)만 있다. 응답·보충(REP-2), 수리 진행(REP-3~5)은 그 기능을 만들 때 더한다. 그 전까지 다른 행동은 {@code
 * INVALID_STATE}다.
 */
@Component
public class RepairTransitions implements Transitions<RepairState> {

  @Override
  public RepairState initial() {
    return RepairState.NONE;
  }

  @Override
  public RepairState apply(RepairState now, ActionEntry action) {
    return switch (action.type()) {
      case REGISTER -> register(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 하자 등록: 시작 전 · 확인 기다림 · 부담 미정(06 REP-1a, 05 2-5). 등록한 측이 기록한 측이다. 측이 없는 주체(서버·운영)는 수리 건을 만들 수
   * 없다({@code repair_case.created_by_side} NOT NULL).
   */
  private static RepairState register(RepairState now, ActionEntry action) {
    if (now.exists() || action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return new RepairState(
        RepairStatus.NOT_STARTED, AckState.PENDING, MoneyStatus.UNDECIDED, action.actorSide());
  }
}
