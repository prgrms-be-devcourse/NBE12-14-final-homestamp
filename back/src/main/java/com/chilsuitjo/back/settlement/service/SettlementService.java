package com.chilsuitjo.back.settlement.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.ReasonRequest;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.settlement.dto.FinishConfirmRequest;
import com.chilsuitjo.back.settlement.dto.FinishResult;
import com.chilsuitjo.back.settlement.dto.SettlementCreated;
import com.chilsuitjo.back.settlement.dto.TransferRequest;
import com.chilsuitjo.back.settlement.repository.SettlementRepository;
import com.chilsuitjo.back.settlement.repository.SettlementStateStore;
import com.chilsuitjo.back.settlement.repository.SettlementTransferRepository;
import com.chilsuitjo.back.settlement.repository.TransferStateStore;
import com.chilsuitjo.back.settlement.rule.SettlementState;
import com.chilsuitjo.back.settlement.rule.SettlementTransitions;
import com.chilsuitjo.back.settlement.rule.TransferState;
import com.chilsuitjo.back.settlement.rule.TransferTransitions;
import java.util.LinkedHashMap;
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
  private final SettlementRepository settlements;
  private final SettlementTransferRepository transfers;
  private final TransferStateStore transferStore;
  private final TransferTransitions transferRules;

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
   * 송금 기록(06 4-8 {@code transfers}, SET-4a·4b). 금액·날짜·방향이 없거나 금액이 1보다 작으면 {@code
   * VALIDATION_FAILED}(pipeline 전). 없는 정산·당사자가 아니면 {@code NOT_FOUND}, 정산 완료면 {@code SETTLED}, 공유를
   * 그만둔 사람이면 {@code SHARING_STOPPED}(pipeline)다. 보내는 측이 남기면 보낸 기록, 받는 측이 남기면 [받았어요(혼자)]다.
   */
  public SettlementCreated recordTransfer(
      UUID settlementId, UUID memberId, String idempotencyKey, TransferRequest request) {
    if (request == null
        || request.direction() == null
        || request.amount() == null
        || request.amount() < 1
        || request.paidOn() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    UUID roomRecordId = roomOf(settlementId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(TransferStateStore.SETTLEMENT_ID, settlementId.toString());
    payload.put(TransferStateStore.DIRECTION, request.direction().name());
    if (request.sameAsTransferId() != null) {
      payload.put(TransferStateStore.SAME_AS, request.sameAsTransferId().toString());
    }
    PipelineResult<?> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RECORD_TRANSFER,
                idempotencyKey,
                null,
                null,
                null,
                null,
                payload),
            transferStore.recording(request.amount(), request.paidOn()),
            transferRules);
    return new SettlementCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 송금 응답(06 4-8 {@code respond}, SET-4). 답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면
   * {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1). 없는 송금·당사자가 아니면 {@code NOT_FOUND}, 그 뒤는 {@code
   * AnswerSupport.answer} 순서다. 내용 지문은 본 판이다.
   */
  public AnswerResult respondTransfer(
      UUID transferId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    UUID roomRecordId =
        transfers
            .findRoomRecordIdById(transferId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    String reason =
        request.reason() == null || request.reason().isBlank() ? null : request.reason();
    PipelineResult<TransferState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RESPOND_TRANSFER,
                idempotencyKey,
                ActionTarget.of(TargetKind.SETTLEMENT_TRANSFER, transferId),
                request.seenVersionNo(),
                transferStore.seenContent(transferId, request.seenVersionNo()).orElse(null),
                reason,
                Map.of("answer", request.answer().name())),
            transferStore.responding(),
            transferRules);
    return new AnswerResult(
        transferId, request.seenVersionNo(), result.state().response(), result.action().replayed());
  }

  /**
   * 정산 끝내기 제안(06 4-8 {@code finish}, SET-5a). 거주 종료 뒤에만, 연결한 상대에게만, 열린 끝내기 요청이 없을 때만({@code
   * INVALID_STATE}). 어느 쪽이든 공유를 그만둔 동안은 {@code SHARING_STOPPED}, [아직 안 끝났어요] 뒤 7일 안이면 {@code
   * ASKED_RECENTLY}다.
   */
  public FinishResult proposeFinish(UUID settlementId, UUID memberId, String idempotencyKey) {
    return finish(
        settlementId,
        memberId,
        idempotencyKey,
        ActionType.PROPOSE_FINISH,
        null,
        null,
        store.proposing());
  }

  /**
   * 정산 끝내기 확인(06 4-8 {@code finish/confirm}, SET-5a·5e). 본 정산 모습 번호가 지금과 다르면 {@code
   * VERSION_CONFLICT}, 요청한 측이면 {@code NOT_COUNTERPART_SIDE}, 열린 요청이 없으면 {@code INVALID_STATE}다.
   * 확인되면 정산 완료다.
   */
  public FinishResult confirmFinish(
      UUID settlementId, UUID memberId, String idempotencyKey, FinishConfirmRequest request) {
    Integer seen = request == null ? null : request.seenVersionNo();
    if (seen == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return finish(
        settlementId,
        memberId,
        idempotencyKey,
        ActionType.CONFIRM_FINISH,
        seen,
        null,
        store.confirming());
  }

  /**
   * [아직 안 끝났어요](06 4-8 {@code finish/not-yet}, SET-5c). 사유가 비었으면 {@code REASON_REQUIRED}, 주민등록번호
   * 형태면 {@code RESIDENT_ID_BLOCKED}다. 끝내기 요청이 닫히고 요청한 쪽에 혼자 끝내기가 열리지 않는다.
   */
  public FinishResult notYetFinish(
      UUID settlementId, UUID memberId, String idempotencyKey, ReasonRequest request) {
    String reason = request == null ? null : request.reason();
    ResidentIdGuard.check(reason);
    return finish(
        settlementId,
        memberId,
        idempotencyKey,
        ActionType.NOT_YET_FINISH,
        null,
        reason == null || reason.isBlank() ? null : reason,
        store.notYet());
  }

  /**
   * 혼자 끝내기(06 4-8 {@code finish/alone}, SET-5d). 혼자 진행 이유가 없으면 {@code SOLO_NOT_ALLOWED}, 공유를 그만둔
   * 동안은 {@code SHARING_STOPPED}다. 정산 완료가 된다.
   */
  public FinishResult finishAlone(UUID settlementId, UUID memberId, String idempotencyKey) {
    return finish(
        settlementId, memberId, idempotencyKey, ActionType.FINISH_ALONE, null, null, store.alone());
  }

  private FinishResult finish(
      UUID settlementId,
      UUID memberId,
      String idempotencyKey,
      ActionType type,
      Integer seenVersionNo,
      String reason,
      StateStore<SettlementState> stateStore) {
    UUID roomRecordId = roomOf(settlementId);
    PipelineResult<SettlementState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                type,
                idempotencyKey,
                ActionTarget.settlement(settlementId),
                seenVersionNo,
                null,
                reason,
                Map.of()),
            stateStore,
            rules);
    SettlementState state = result.state();
    return new FinishResult(
        settlementId, state.settled(), state.finishRequestedBy(), result.action().replayed());
  }

  private UUID roomOf(UUID settlementId) {
    return settlements
        .findRoomRecordIdById(settlementId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
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
