package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.dto.DefectAckChanged;
import com.chilsuitjo.back.repair.dto.RegisterRepairRequest;
import com.chilsuitjo.back.repair.dto.RepairCaseCreated;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
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

  /** 보충 설명 길이(하자 등록의 설명과 같음, 06 4-6). */
  static final int MAX_DESCRIPTION = 2000;

  private final ActionPipeline pipeline;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RepairCaseRepository cases;

  /**
   * 하자 등록(06 REP-1). 제목·설명에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1), 당사자가
   * 아니면 {@code NOT_FOUND}, 거주 종료면 {@code ROOM_ENDED}(pipeline), 잘못된 키면 {@code VALIDATION_FAILED}다.
   * 내용 지문은 {@code save}가 만든 판 #1로 남는다(새 판을 만드는 행동, 안내서 3-3).
   */
  public RepairCaseCreated register(
      UUID roomRecordId, UUID memberId, String idempotencyKey, RegisterRepairRequest request) {
    ResidentIdGuard.check(request.title(), request.description());
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

  /**
   * 하자 응답(06 REP-2a~d·f, REP-3, REP-9a). 답이 없으면 {@code VALIDATION_FAILED}, 없는 수리 건·당사자가 아니면 {@code
   * NOT_FOUND}, 그 밖의 거절은 {@code AnswerSupport.answer} 순서다(상대 측 → 판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만 →
   * 사유).
   *
   * <p>답은 payload {@code answer}, 사유는 행동 기록의 {@code reason}에 남긴다(#155). 내용 지문은 본 판이다(안내서 3-3). 같은
   * 키로 다시 오면 처음 결과를 저장하지 않으므로 수리 건의 지금 하자 인정을 돌려준다({@code AnswerResult}, 06 4-0).
   */
  public AnswerResult respond(
      UUID repairCaseId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    UUID roomRecordId = roomOf(repairCaseId);
    PipelineResult<RepairState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RESPOND_DEFECT,
                idempotencyKey,
                ActionTarget.repairCase(repairCaseId),
                request.seenVersionNo(),
                store.seenContent(repairCaseId, request.seenVersionNo()).orElse(null),
                blankToNull(request.reason()),
                Map.of("answer", request.answer().name())),
            store.responding(),
            rules);
    return new AnswerResult(
        repairCaseId,
        request.seenVersionNo(),
        result.state().defectAck(),
        result.action().replayed());
  }

  /**
   * 보충하고 다시 확인 요청(06 REP-2e). pipeline 전에 입력을 본다: 사진 칸은 아직 받지 않아 {@code VALIDATION_FAILED}, 설명이
   * 2,000자를 넘으면 {@code VALIDATION_FAILED}, 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 근거가 없으면 {@code
   * EVIDENCE_REQUIRED}. 그 뒤는 {@code AnswerSupport.rerequest} 순서다(등록 측 → 보류·이견이고 요청을 열 수 있음 → 7일).
   *
   * <p>사진(업로드 표)은 #153 {@code PhotoService}와 #112 실제 업로드 뒤에 붙인다. 그 전에 사진이 오면 조용히 버리지 않고 거절한다.
   */
  public DefectAckChanged supplement(
      UUID repairCaseId, UUID memberId, String idempotencyKey, SupplementRequest request) {
    if (request == null || !request.uploadTickets().isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String description = blankToNull(request.description());
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(description);
    AnswerSupport.requireEvidence(description != null);
    UUID roomRecordId = roomOf(repairCaseId);
    PipelineResult<RepairState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.SUPPLEMENT,
                idempotencyKey,
                ActionTarget.repairCase(repairCaseId),
                null,
                null,
                null,
                Map.of()),
            store.supplementing(description),
            rules);
    return new DefectAckChanged(
        repairCaseId, result.state().defectAck(), result.action().replayed());
  }

  /**
   * 수리 건의 호실. 없는 ID면 {@code NOT_FOUND}. 남의 수리 건도 pipeline 잠금의 당사자 확인에서 같은 {@code NOT_FOUND}가 된다.
   */
  private UUID roomOf(UUID repairCaseId) {
    return cases
        .findRoomRecordIdById(repairCaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
