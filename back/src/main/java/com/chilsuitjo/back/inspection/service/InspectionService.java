package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.dto.AddInspectionItemRequest;
import com.chilsuitjo.back.inspection.dto.InspectionCreated;
import com.chilsuitjo.back.inspection.dto.InspectionStatusChanged;
import com.chilsuitjo.back.inspection.dto.StartInspectionRequest;
import com.chilsuitjo.back.inspection.repository.InspectionItemStateStore;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionStateStore;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.room.service.RoomMoveInDates;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 점검 행동을 pipeline에 넘긴다. 트랜잭션은 pipeline이 연다(09 3-1 "트랜잭션 경계"): 이 서비스에는 {@code @Transactional}을 두지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
public class InspectionService {

  private final ActionPipeline pipeline;
  private final InspectionStateStore inspectionStore;
  private final InspectionTransitions inspectionRules;
  private final InspectionItemStateStore itemStore;
  private final InspectionItemTransitions itemRules;
  private final InspectionRepository inspections;

  /**
   * 점검 시작(06 4-7, INSP-1a·2b). 당사자가 아니면 {@code NOT_FOUND}, 이미 같은 종류 점검이 있으면 {@code
   * INSPECTION_EXISTS}, 호실 입주일이 비었는데 {@code moveInDate}가 없으면 {@code MOVE_IN_DATE_REQUIRED}다. 입주일은
   * 행동 payload에 남고 같은 트랜잭션에서 호실에 저장된다.
   */
  public InspectionCreated start(
      UUID roomRecordId, UUID memberId, String idempotencyKey, StartInspectionRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(InspectionStateStore.KIND, request.kind().name());
    if (request.moveInDate() != null) {
      payload.put(RoomMoveInDates.FILLED_PAYLOAD_KEY, request.moveInDate().toString());
    }
    PipelineResult<?> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.START_INSPECTION,
                idempotencyKey,
                null,
                null,
                null,
                null,
                payload),
            inspectionStore.starting(),
            inspectionRules);
    return new InspectionCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 점검 항목 더하기(06 4-7, 03 H "항목"). 설명에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03
   * 6-1). 없는 점검·당사자가 아니면 {@code NOT_FOUND}, 작성 중 기준 측이 아니면 {@code INVALID_STATE}, 정산 완료면 {@code
   * SETTLED}(pipeline)다. 내용 지문은 {@code save}가 만든 판 #1로, 사진 지문은 행동 payload {@code photos}로 남는다.
   */
  public InspectionCreated addItem(
      UUID inspectionId, UUID memberId, String idempotencyKey, AddInspectionItemRequest request) {
    ResidentIdGuard.check(request.description());
    UUID roomRecordId =
        inspections
            .findRoomRecordIdById(inspectionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    PipelineResult<?> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.ADD_ITEM,
                idempotencyKey,
                null,
                null,
                null,
                null,
                Map.of(InspectionItemStateStore.INSPECTION_ID, inspectionId.toString())),
            itemStore.adding(request.roomPartId(), request.description(), request.uploadTickets()),
            itemRules);
    return new InspectionCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 확인 요청(06 4-7 {@code request-confirm}, INSP-1f). 없는 점검·당사자가 아니면 {@code NOT_FOUND}, 작성 중이 아니거나 상대
   * 측이 {@code inspection.draft.stale-after} 전에 보내면 {@code INVALID_STATE}, 공유를 그만둔 사람이면 {@code
   * SHARING_STOPPED}(pipeline)다. 같은 키로 다시 오면 점검의 지금 상태를 돌려준다(06 4-0).
   */
  public InspectionStatusChanged requestConfirm(
      UUID inspectionId, UUID memberId, String idempotencyKey) {
    UUID roomRecordId =
        inspections
            .findRoomRecordIdById(inspectionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    PipelineResult<InspectionState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.REQUEST_INSPECTION_CONFIRM,
                idempotencyKey,
                ActionTarget.inspection(inspectionId),
                null,
                null,
                null,
                Map.of()),
            inspectionStore.requesting(),
            inspectionRules);
    return new InspectionStatusChanged(
        inspectionId, result.state().status(), result.action().replayed());
  }
}
