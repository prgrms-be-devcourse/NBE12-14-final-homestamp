package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.dto.AddInspectionItemRequest;
import com.chilsuitjo.back.inspection.dto.ConfirmAllRequest;
import com.chilsuitjo.back.inspection.dto.InspectionCreated;
import com.chilsuitjo.back.inspection.dto.InspectionStatusChanged;
import com.chilsuitjo.back.inspection.dto.StartInspectionRequest;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionItemStateStore;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionStateStore;
import com.chilsuitjo.back.inspection.rule.ConfirmAllTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.room.service.RoomMoveInDates;
import com.chilsuitjo.back.settlement.service.SettlementService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
  private final ConfirmAllTransitions confirmAllRules;
  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final SettlementService settlements;

  /** 보충 설명 길이(06 4-0, 수리 건 보충과 같은 값). */
  static final int MAX_DESCRIPTION = 2000;

  /** 한 번에 붙이는 사진(업로드 표 1~10개, 06 4-0 업로드). */
  static final int MAX_PHOTOS = 10;

  /**
   * 점검 시작(06 4-7, INSP-1a·2b). 당사자가 아니면 {@code NOT_FOUND}, 이미 같은 종류 점검이 있으면 {@code
   * INSPECTION_EXISTS}, 호실 입주일이 비었는데 {@code moveInDate}가 없으면 {@code MOVE_IN_DATE_REQUIRED}다. 입주일은
   * 행동 payload에 남고 같은 트랜잭션에서 호실에 저장된다. 퇴거 점검이면 같은 요청에서 정산을 연다({@code OPEN_SETTLEMENT}, 03 H·I,
   * #320). 이미 정산이 있으면 열지 않고, 점검 시작이 거절되면 정산도 열리지 않는다.
   */
  public InspectionCreated start(
      UUID roomRecordId, UUID memberId, String idempotencyKey, StartInspectionRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(InspectionStateStore.KIND, request.kind().name());
    if (request.moveInDate() != null) {
      payload.put(RoomMoveInDates.FILLED_PAYLOAD_KEY, request.moveInDate().toString());
    }
    if (request.kind() == InspectionKind.MOVE_OUT) {
      // 퇴거 점검을 시작하면 같은 요청에서 정산이 열린다(03 H·I, #320). 이미 있으면 건너뛴다
      BatchResult opened =
          pipeline.executeAll(
              ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                  .step(
                      ActionStep.creating(
                              ActionType.START_INSPECTION,
                              inspectionStore.starting(),
                              inspectionRules)
                          .withPayload(payload))
                  .step(settlements.openStep()));
      return new InspectionCreated(opened.first().target().id(), opened.first().replayed());
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
   * 6-1). 없는 점검·당사자가 아니면 {@code NOT_FOUND}, 숨긴 부위·꺼 둔 설비·가전이면 {@code VALIDATION_FAILED}, 정산 완료면
   * {@code SETTLED}(pipeline)다. 누구든 점검이 있는 동안 더한다(INSP-1d·1e, #286). 내용 지문은 {@code save}가 만든 판 #1로,
   * 사진 지문은 행동 payload {@code photos}로 남는다.
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

  /**
   * 항목 응답(06 4-7 {@code respond}). 답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면 {@code
   * RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1). 없는 항목·당사자가 아니면 {@code NOT_FOUND}, 작성 중인 기준 측
   * 항목이거나(상대가 더한 항목은 작성 중에도 답함, #286) 답하는 사람에게 보이지 않는 항목이면 {@code INVALID_STATE}, 그 뒤는 {@code
   * AnswerSupport.answer} 순서다. 내용 지문은 본 판이다(안내서 3-3).
   */
  public AnswerResult respond(
      UUID itemId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    UUID roomRecordId = roomOfItem(itemId);
    PipelineResult<InspectionItemState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RESPOND_ITEM,
                idempotencyKey,
                itemTarget(itemId),
                request.seenVersionNo(),
                itemStore.seenContent(itemId, request.seenVersionNo()).orElse(null),
                blankToNull(request.reason()),
                Map.of("answer", request.answer().name())),
            itemStore.responding(),
            itemRules);
    return new AnswerResult(
        itemId, request.seenVersionNo(), result.state().status(), result.action().replayed());
  }

  /**
   * 남은 항목 모두 확인(06 4-7 {@code confirm-all}, 03 1-4 "한 번에 확인"). {@code CONFIRM_ALL} 한 줄과 본 항목마다
   * {@code RESPOND_ITEM}(맞아요)을 한 요청·한 트랜잭션으로 남긴다: 어느 단계든 거절되면 아무것도 남지 않는다. 본 항목·판 목록이 지금 미응답 항목과
   * 다르면 {@code VERSION_CONFLICT}, 항목이 없는 점검이면 "하자 기록 없음" 확인이다({@code ConfirmAllTransitions}).
   */
  public InspectionStatusChanged confirmAll(
      UUID inspectionId, UUID memberId, String idempotencyKey, ConfirmAllRequest request) {
    UUID roomRecordId = roomOf(inspectionId);
    List<Map<String, Object>> seen = new ArrayList<>();
    for (ConfirmAllRequest.Seen item : request.items()) {
      seen.add(
          Map.of(
              ConfirmAllTransitions.ITEM_ID,
              item.id().toString(),
              ConfirmAllTransitions.SEEN_VERSION_NO,
              item.seenVersionNo()));
    }
    ActionTarget inspection = ActionTarget.inspection(inspectionId);
    ActionBatch batch =
        ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
            .step(
                ActionStep.of(
                        ActionType.CONFIRM_ALL,
                        done -> inspection,
                        itemStore.confirmingAll(),
                        confirmAllRules)
                    .withPayload(Map.of(ConfirmAllTransitions.ITEMS, List.copyOf(seen))));
    for (ConfirmAllRequest.Seen item : request.items()) {
      ActionTarget target = itemTarget(item.id());
      batch.step(
          ActionStep.of(ActionType.RESPOND_ITEM, done -> target, itemStore.responding(), itemRules)
              .withSeenVersionNo(item.seenVersionNo())
              .withContent(itemStore.seenContent(item.id(), item.seenVersionNo()).orElse(null))
              .withPayload(Map.of("answer", Answer.ACK.name())));
    }
    boolean replayed = pipeline.executeAll(batch).replayed();
    // executeAll은 상태를 돌려주지 않아 커밋된 행을 다시 읽는다(같은 키로 다시 왔으면 지금 값, 06 4-0)
    Inspection row =
        inspections
            .findById(inspectionId)
            .orElseThrow(() -> new IllegalStateException("점검이 없어요: " + inspectionId));
    return new InspectionStatusChanged(row.getId(), row.getStatus(), replayed);
  }

  /**
   * 보충하고 다시 확인 요청(06 4-7 {@code supplement}). pipeline 전에 입력을 본다: 사진이 10장을 넘거나 빈 표가 있거나 설명이 2,000자를
   * 넘으면 {@code VALIDATION_FAILED}, 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 사진도 설명도 없으면 {@code
   * EVIDENCE_REQUIRED}. 그 뒤는 {@code AnswerSupport.rerequest} 순서다(더한 측 → 보류·이견이고 요청을 열 수 있음 → 7일).
   * 사진은 항목에 붙는다.
   */
  public AnswerResult supplement(
      UUID itemId, UUID memberId, String idempotencyKey, SupplementRequest request) {
    if (request == null
        || request.uploadTickets().size() > MAX_PHOTOS
        || request.uploadTickets().stream().anyMatch(t -> t == null || t.isBlank())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String description = blankToNull(request.description());
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(description);
    AnswerSupport.requireEvidence(request.hasEvidence());
    UUID roomRecordId = roomOfItem(itemId);
    PipelineResult<InspectionItemState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.SUPPLEMENT_ITEM,
                idempotencyKey,
                itemTarget(itemId),
                null,
                null,
                null,
                Map.of()),
            itemStore.supplementing(description, request.uploadTickets()),
            itemRules);
    return new AnswerResult(
        itemId, result.state().versionNo(), result.state().status(), result.action().replayed());
  }

  private UUID roomOf(UUID inspectionId) {
    return inspections
        .findRoomRecordIdById(inspectionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private UUID roomOfItem(UUID itemId) {
    return items
        .findRoomRecordIdById(itemId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static ActionTarget itemTarget(UUID itemId) {
    return ActionTarget.of(TargetKind.INSPECTION_ITEM, itemId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
