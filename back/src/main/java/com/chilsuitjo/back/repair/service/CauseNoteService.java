package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.dto.CauseNoteCreated;
import com.chilsuitjo.back.repair.dto.CauseNoteRequest;
import com.chilsuitjo.back.repair.repository.CauseNoteStateStore;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.CauseNoteState;
import com.chilsuitjo.back.repair.rule.CauseNoteTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 원인 설명 행동을 pipeline에 넘긴다(06 REP-8). 트랜잭션은 pipeline이 연다(09 3-1): {@code @Transactional}을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CauseNoteService {

  /** 설명 길이(하자 설명과 같음, 06 4-6). */
  static final int MAX_TEXT = 2000;

  /** 업체·전문가 이름 길이(V1 {@code cause_note.expert_name}). */
  static final int MAX_EXPERT_NAME = 50;

  private final ActionPipeline pipeline;
  private final CauseNoteStateStore store;
  private final CauseNoteTransitions rules;
  private final RepairCaseRepository cases;

  /**
   * 원인 설명 남기기·고치기(06 REP-8a·c·d). pipeline 전에 입력을 본다: 근거 종류가 없으면 {@code BASIS_REQUIRED}(REP-8a),
   * 설명이 없거나 2,000자를 넘거나, 업체 이름이 50자를 넘거나 업체·전문가 소견이 아닌데 업체 이름이 있거나, 사진이 10장을 넘거나 빈 표가 있으면 {@code
   * VALIDATION_FAILED}, 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}(03 6-1). 그 뒤 당사자가 아니면 {@code
   * NOT_FOUND}, 볼 수 없는 수리 건이면 {@code INVALID_STATE}, 고칠 설명이 내 지금 설명과 다르면 {@code
   * VERSION_CONFLICT}(REP-8d)다.
   *
   * <p>고치면(문장만 바꾸든 근거 종류를 바꾸든) 언제나 새 설명이 남고, 이전 설명과 그 응답은 이력이 된다(REP-8c). 고치기 장치({@code
   * corrections})를 쓰지 않는다. 거주 종료 뒤에도 정산 완료 전까지 된다(pipeline {@code SETTLED}가 막음). 공유를 그만둔 사람은 남기지
   * 못한다({@code SHARING_STOPPED}).
   */
  public CauseNoteCreated note(
      UUID repairCaseId, UUID memberId, String idempotencyKey, CauseNoteRequest request) {
    if (request == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    if (request.basis() == null) {
      throw new BusinessException(ErrorCode.BASIS_REQUIRED);
    }
    if (request.text() == null
        || request.text().length() > MAX_TEXT
        || (request.expertName() != null
            && (request.basis() != CauseBasis.EXPERT
                || request.expertName().length() > MAX_EXPERT_NAME))
        || request.uploadTickets().size() > RepairService.MAX_PHOTOS
        || request.uploadTickets().stream().anyMatch(t -> t == null || t.isBlank())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.text(), request.expertName());
    UUID roomRecordId = roomOfCase(repairCaseId);
    Map<String, Object> payload =
        request.previousId() == null
            ? Map.of()
            : Map.of(CauseNoteTransitions.NotePayload.PREVIOUS_ID, request.previousId().toString());
    PipelineResult<CauseNoteState> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.NOTE_CAUSE,
                idempotencyKey,
                null,
                null,
                null,
                null,
                payload),
            store.noting(
                repairCaseId,
                request.text(),
                request.basis(),
                request.expertName(),
                request.previousId(),
                request.uploadTickets()),
            rules);
    return new CauseNoteCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 원인 설명에 응답(06 REP-8b·c). 답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면 {@code
   * RESIDENT_ID_BLOCKED}(pipeline 전), 없는 설명·당사자가 아님·볼 수 없는 설명은 {@code NOT_FOUND}, 내가 남긴 설명이면 {@code
   * NOT_COUNTERPART_SIDE}, 이미 고쳐진 설명이면 {@code VERSION_CONFLICT}, 그 뒤는 {@code AnswerSupport.answer}
   * 순서다(맞아요는 끝 {@code ACK_FINAL}, 보류·이견에서는 맞아요로만 {@code INVALID_STATE}, 사유 {@code
   * REASON_REQUIRED}).
   *
   * <p>답은 payload {@code answer}, 사유는 행동 기록의 {@code reason}에 남고(#155) 내용 지문은 답한 설명이다. 응답 요청·문자·기다림은
   * 없고 부담 합의·하자 인정은 그대로다(REP-8b). {@code seenVersionNo}는 보지 않는다(설명은 판이 없고, 고치면 새 설명이라 설명 ID가 본
   * 모습이다).
   */
  public AnswerResult respond(
      UUID causeNoteId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    UUID roomRecordId = store.roomOf(causeNoteId);
    PipelineResult<CauseNoteState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RESPOND_CAUSE,
                idempotencyKey,
                ActionTarget.of(TargetKind.CAUSE_NOTE, causeNoteId),
                null,
                store.seenContent(causeNoteId).orElse(null),
                request.reason() == null || request.reason().isBlank() ? null : request.reason(),
                Map.of("answer", request.answer().name())),
            store.responding(),
            rules);
    return new AnswerResult(causeNoteId, null, result.state().answer(), result.action().replayed());
  }

  private UUID roomOfCase(UUID repairCaseId) {
    return cases
        .findRoomRecordIdById(repairCaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }
}
