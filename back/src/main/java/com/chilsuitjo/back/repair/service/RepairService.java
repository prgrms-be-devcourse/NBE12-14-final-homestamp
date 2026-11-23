package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.repair.dto.RegisterRepairRequest;
import com.chilsuitjo.back.repair.dto.RepairCaseCreated;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 수리 건 행동을 pipeline에 넘긴다. 트랜잭션은 pipeline이 연다(09 3-1 "트랜잭션 경계"): 이 서비스에는 {@code @Transactional}을 두지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class RepairService {

  private final ActionPipeline pipeline;
  private final RepairStateStore store;
  private final RepairTransitions rules;

  /**
   * 하자 등록(06 REP-1). 당사자가 아니면 {@code NOT_FOUND}, 거주 종료면 {@code ROOM_ENDED}(pipeline), 잘못된 키면 {@code
   * VALIDATION_FAILED}다. 내용 지문은 {@code save}가 만든 판 #1로 남는다(새 판을 만드는 행동, 안내서 3-3).
   */
  public RepairCaseCreated register(
      UUID roomRecordId, UUID memberId, String idempotencyKey, RegisterRepairRequest request) {
    PipelineResult<RepairState> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.REGISTER,
                idempotencyKey,
                null,
                null,
                null,
                null,
                Map.of()),
            store.registering(request.roomPartId(), request.title(), request.description()),
            rules);
    return new RepairCaseCreated(result.action().target().id(), result.action().replayed());
  }
}
