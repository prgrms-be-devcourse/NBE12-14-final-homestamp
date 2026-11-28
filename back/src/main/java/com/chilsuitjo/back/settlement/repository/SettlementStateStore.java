package com.chilsuitjo.back.settlement.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.settlement.entity.Settlement;
import com.chilsuitjo.back.settlement.rule.SettlementState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 정산 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * SettlementTransitions}에만).
 */
@Component
@RequiredArgsConstructor
public class SettlementStateStore {

  private final SettlementRepository settlements;
  private final ActionRequestContext requestContext;

  /** 정산 열기(06 4-8, SET-1)의 저장 방법. 퇴거 점검 시작이 같은 요청에서 열 때도 이것을 쓴다(#320). */
  public StateStore<SettlementState> opening() {
    return new Opening();
  }

  private final class Opening implements StateStore<SettlementState> {

    /**
     * 그 호실에 정산이 있으면 열린 상태, 없으면 아직 없는 상태다. 호실을 잠근 뒤라 동시에 열어도 뒤에 온 쪽은 먼저 만든 정산을 본다(03 1-3). 퇴거 점검 시작의
     * {@code when}도 이 값을 본다.
     */
    @Override
    public SettlementState load(ActionCommand command) {
      return settlements.findByRoomRecordId(command.roomRecordId()).isPresent()
          ? SettlementState.OPENED
          : SettlementState.NONE;
    }

    /** 정산을 만든다. 처음 상태는 보증금 확인 전, 정산 모습 1번({@code Settlement} 생성자). */
    @Override
    public SavedState save(ActionCommand command, SettlementState next) {
      Settlement row =
          settlements.save(new Settlement(command.roomRecordId(), requestContext.at()));
      return SavedState.created(ActionTarget.settlement(row.getId()));
    }
  }
}
