package com.chilsuitjo.back.settlement.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.settlement.dto.SettlementCreated;
import com.chilsuitjo.back.settlement.repository.SettlementStateStore;
import com.chilsuitjo.back.settlement.rule.SettlementState;
import com.chilsuitjo.back.settlement.rule.SettlementTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 정산 행동을 pipeline에 넘긴다. 트랜잭션은 pipeline이 연다(09 3-1 "트랜잭션 경계"): 이 서비스에는 {@code @Transactional}을 두지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

  private final ActionPipeline pipeline;
  private final SettlementStateStore store;
  private final SettlementTransitions rules;

  /**
   * 정산 열기(06 4-8, SET-1). 당사자 누구든 거주 중·거주 종료에 연다(03 I). 당사자가 아니면 {@code NOT_FOUND}, 정산 완료면 {@code
   * SETTLED}, 공유를 그만둔 사람이면 {@code SHARING_STOPPED}(pipeline), 이미 열렸으면 {@code SETTLEMENT_EXISTS}다.
   */
  public SettlementCreated open(UUID roomRecordId, UUID memberId, String idempotencyKey) {
    PipelineResult<?> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.OPEN_SETTLEMENT,
                idempotencyKey,
                null,
                null,
                null,
                null,
                Map.of()),
            store.opening(),
            rules);
    return new SettlementCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 다른 행동과 같은 요청에서 정산을 여는 단계(퇴거 점검 시작, 03 H·I, #320). 이미 정산이 있으면 기록 없이 건너뛴다({@code when}, 09 3-1
   * {@code ActionStep}). 같은 트랜잭션이라 앞 단계가 거절되면 정산도 열리지 않는다.
   */
  public ActionStep<SettlementState> openStep() {
    return ActionStep.creating(ActionType.OPEN_SETTLEMENT, store.opening(), rules)
        .when(now -> !now.opened());
  }
}
