package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.ReasonRequest;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.dto.ProposalRequest;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.service.ProposalService;
import com.chilsuitjo.back.repair.dto.ConfirmCloseRequest;
import com.chilsuitjo.back.repair.dto.DefectAckChanged;
import com.chilsuitjo.back.repair.dto.ProgressAsked;
import com.chilsuitjo.back.repair.dto.ProgressNoteCreated;
import com.chilsuitjo.back.repair.dto.ProgressNoteRequest;
import com.chilsuitjo.back.repair.dto.RegisterRepairRequest;
import com.chilsuitjo.back.repair.dto.RepairCaseCreated;
import com.chilsuitjo.back.repair.dto.RepairProgressChanged;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.repository.ProgressNoteRepository;
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

  /** 한 번에 붙이는 사진(업로드 표 1~10개, 06 4-0 업로드). 하자 등록은 요청 본문의 검증 표시가 같은 값으로 막는다. */
  static final int MAX_PHOTOS = 10;

  private final ActionPipeline pipeline;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RepairCaseRepository cases;
  private final ProposalService proposals;
  private final RoomGateReader gates;
  private final ProgressNoteRepository notes;

  /**
   * 하자 등록(06 REP-1). pipeline 전에 사진이 없으면 {@code PHOTO_REQUIRED}(REP-1d), 제목·설명에 주민등록번호 형태가 있으면
   * {@code RESIDENT_ID_BLOCKED}(03 6-1). 그 뒤 당사자가 아니면 {@code NOT_FOUND}, 거주 종료면 {@code
   * ROOM_ENDED}(pipeline), 잘못된 키면 {@code VALIDATION_FAILED}, 사진 검사에 실패하면 {@code
   * PHOTO_CHECK_FAILED}·{@code UPLOAD_TICKET_*}(사진 번호와 함께, 수리 건·판도 남지 않음)다. 내용 지문은 {@code save}가 만든
   * 판 #1로 남고(새 판을 만드는 행동, 안내서 3-3), 사진 지문은 행동 payload {@code photos}로 남는다(05 2-3).
   */
  public RepairCaseCreated register(
      UUID roomRecordId, UUID memberId, String idempotencyKey, RegisterRepairRequest request) {
    if (request.uploadTickets().isEmpty()) {
      throw new BusinessException(ErrorCode.PHOTO_REQUIRED);
    }
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
            store.registering(
                request.roomPartId(),
                request.title(),
                request.description(),
                request.uploadTickets()),
            rules);
    return new RepairCaseCreated(result.action().target().id(), result.action().replayed());
  }

  /**
   * 하자 응답(06 REP-2a~d·f, REP-3, REP-9a). 답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면
   * {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1. 사유는 고칠 수 없는 행동 기록에 남는다), 없는 수리 건·당사자가 아니면
   * {@code NOT_FOUND}, 상대가 볼 수 없는 하자면 {@code INVALID_STATE}, 그 밖의 거절은 {@code AnswerSupport.answer}
   * 순서다(상대 측 → 판 번호 → 맞아요는 끝 → 보류·이견에서는 맞아요로만 → 사유).
   *
   * <p>답은 payload {@code answer}, 사유는 행동 기록의 {@code reason}에 남긴다(#155). 내용 지문은 본 판이다(안내서 3-3). 같은
   * 키로 다시 오면 처음 결과를 저장하지 않으므로 수리 건의 지금 하자 인정을 돌려준다({@code AnswerResult}, 06 4-0).
   */
  public AnswerResult respond(
      UUID repairCaseId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
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
   * 보충하고 다시 확인 요청(06 REP-2e). pipeline 전에 입력을 본다: 사진이 10장을 넘거나 빈 표가 있거나 설명이 2,000자를 넘으면 {@code
   * VALIDATION_FAILED}, 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 사진도 설명도 없으면 {@code
   * EVIDENCE_REQUIRED}. 그 뒤는 {@code AnswerSupport.rerequest} 순서다(등록 측 → 보류·이견이고 요청을 열 수 있음 → 7일).
   * 사진 검사 실패는 {@code PHOTO_CHECK_FAILED}·{@code UPLOAD_TICKET_*}(사진 번호와 함께)이고 그때는 보충도 남지 않는다.
   *
   * <p>사진만으로도 보충할 수 있다(설명은 선택). 사진은 수리 건에 붙는다(#167).
   */
  public DefectAckChanged supplement(
      UUID repairCaseId, UUID memberId, String idempotencyKey, SupplementRequest request) {
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
            store.supplementing(description, request.uploadTickets()),
            rules);
    return new DefectAckChanged(
        repairCaseId, result.state().defectAck(), result.action().replayed());
  }

  /**
   * 수리 건의 호실. 없는 ID면 {@code NOT_FOUND}. 남의 수리 건도 pipeline 잠금의 당사자 확인에서 같은 {@code NOT_FOUND}가 된다.
   */
  /**
   * 수리비 부담 제안(06 MONEY-3). 이 수리 건의 호실을 찾아 돈 안건 장치에 넘긴다(decisions 2026-11-17 "이 호실의 수리 건인지 확인한 뒤
   * 부른다"). 없는 수리 건이면 {@code NOT_FOUND}이고, 그 호실 당사자가 아니면 pipeline 잠금에서 {@code NOT_FOUND}다. 줄 모양·기다리는
   * 안·합계 검사는 {@code ProposalService.propose}가 한다. 돈은 수리 진행과 따로라 수리 상태는 보지 않는다.
   */
  public ProposalResult propose(
      UUID repairCaseId, UUID memberId, String idempotencyKey, ProposalRequest request) {
    if (request == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return proposals.propose(
        roomOf(repairCaseId),
        memberId,
        idempotencyKey,
        new AgendaRef(AgendaKind.REPAIR_BURDEN, repairCaseId),
        new ProposalCommand(request.lines(), request.isNoCost(), false, request.reason()));
  }

  /** 수리 시작(06 REP-4a). 시작 전이 아니면 {@code INVALID_STATE}. */
  public RepairProgressChanged start(UUID repairCaseId, UUID memberId, String idempotencyKey) {
    return progress(repairCaseId, memberId, idempotencyKey, ActionType.START_REPAIR, null);
  }

  /**
   * 종료 요청(06 REP-5). 처리 중이 아니거나 지금 종료 확인 요청을 열 수 없으면(거주 종료 뒤) {@code INVALID_STATE}. 종료 확인 요청을 연다.
   */
  public RepairProgressChanged requestClose(
      UUID repairCaseId, UUID memberId, String idempotencyKey) {
    return progress(repairCaseId, memberId, idempotencyKey, ActionType.REQUEST_CLOSE, null);
  }

  /**
   * 혼자 종료(06 REP-5e). 물을 상대가 처음부터 없을 때만(상대 없음·KEEP-4 뒤 상대 탈퇴, {@code canGoSoloDirectly}) 종료 요청 없이
   * 처리 완료(○○ 확인). 상대가 있으면(미확인·수신 거부·문자 안 감·공유 중단·연결 전 포함)이나 종료 확인 대기면 {@code SOLO_NOT_ALLOWED}, 시작
   * 전·처리 완료면 {@code INVALID_STATE}, 거주 종료 뒤면 {@code ROOM_ENDED}.
   */
  public RepairProgressChanged closeAlone(UUID repairCaseId, UUID memberId, String idempotencyKey) {
    return progress(
        repairCaseId, memberId, idempotencyKey, ActionType.CLOSE_ALONE, null, store.closingAlone());
  }

  /**
   * 아직 안 됐어요(06 REP-5b·5f). 사유에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1), 종료
   * 확인 대기·혼자 끝낸 처리 완료가 아니면 {@code INVALID_STATE}, 요청했거나 혼자 끝낸 측이면 {@code NOT_COUNTERPART_SIDE}, 사유가
   * 없으면 {@code REASON_REQUIRED}다. 사유는 행동 기록의 {@code reason}에 남는다. 혼자 끝낸 건이면 상태는 그대로이고 이견으로 남는다.
   */
  public RepairProgressChanged notYet(
      UUID repairCaseId, UUID memberId, String idempotencyKey, ReasonRequest request) {
    String reason = request == null ? null : blankToNull(request.reason());
    ResidentIdGuard.check(reason);
    return progress(repairCaseId, memberId, idempotencyKey, ActionType.NOT_YET, reason);
  }

  /**
   * 종료 확인(06 REP-5a·5f). 종료 확인 대기·혼자 끝낸 처리 완료가 아니면 {@code INVALID_STATE}, 요청했거나 혼자 끝낸 측이면 {@code
   * NOT_COUNTERPART_SIDE}. {@code alsoAckDefect}면 하자 인정이 확인 기다림이고 확인하는 사람이 하자를 등록한 측이 아닐 때만 같은 요청에
   * 하자 "맞아요"를 남긴다({@code CONFIRM_CLOSE} → {@code RESPOND_DEFECT}, REP-3a, decisions
   * 2026-11-17·11-24): 어느 단계든 거절되면 둘 다 남지 않는다. 하자 "맞아요"는 등록한 측의 상대만 하고(03 3장 "맞아요: 상대 측"), 종료 확인은
   * 종료 요청의 상대가 하므로(같은 표 "종료 확인: 요청의 상대 측") 등록한 측의 상대가 종료를 요청했으면 확인하는 쪽이 등록한 측이다. 그때는 함께 남길 응답만 건너뛰고
   * 종료 확인은 남긴다(하자 인정이 확인 기다림이 아닐 때와 같음). 하자 응답 단계의 내용 지문은 본 판이다(안내서 3-3).
   */
  public RepairProgressChanged confirmClose(
      UUID repairCaseId, UUID memberId, String idempotencyKey, ConfirmCloseRequest request) {
    boolean ackDefect = request != null && request.ackDefect();
    UUID roomRecordId = roomOf(repairCaseId);
    // 자리의 측은 바뀌지 않아 잠그지 않고 읽는다. 당사자가 아니면 여기서 NOT_FOUND(pipeline과 같은 거절)
    PartySide confirmer =
        ackDefect ? gates.read(roomRecordId, Actor.member(memberId)).side() : null;
    ActionTarget target = ActionTarget.repairCase(repairCaseId);
    ActionBatch batch =
        ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
            .step(
                ActionStep.of(
                    ActionType.CONFIRM_CLOSE, done -> target, store.progressing(), rules));
    if (ackDefect) {
      Integer seen = request.seenVersionNo();
      batch.step(
          ActionStep.of(ActionType.RESPOND_DEFECT, done -> target, store.responding(), rules)
              .when(state ->
                  state.defectAck() == AckState.PENDING && state.registeredBy() != confirmer)
              .withSeenVersionNo(seen)
              .withContent(store.seenContent(repairCaseId, seen).orElse(null))
              .withPayload(Map.of("answer", Answer.ACK.name())));
    }
    boolean replayed = pipeline.executeAll(batch).replayed();
    // executeAll은 상태를 돌려주지 않아 커밋된 행을 다시 읽는다(같은 키로 다시 왔으면 지금 값, 06 4-0)
    RepairCase row =
        cases
            .findById(repairCaseId)
            .orElseThrow(() -> new IllegalStateException("수리 건이 없어요: " + repairCaseId));
    return new RepairProgressChanged(
        row.getId(), row.getStatus(), row.getClosedBy(), row.getDefectAck(), replayed);
  }

  /**
   * 진행 상황 묻기(06 REP-6b). 시작 전·처리 중이 아니거나 거주 중이 아니면 {@code INVALID_STATE}, 한 건에 7일 안에 누가 이미 물었으면
   * {@code ASKED_RECENTLY}. 상대에게 진행 상황 묻기 요청을 연다. 상대가 그 건에 무엇이든 남기면 닫힌다(REP-6c).
   */
  public ProgressAsked askProgress(UUID repairCaseId, UUID memberId, String idempotencyKey) {
    UUID roomRecordId = roomOf(repairCaseId);
    PipelineResult<RepairState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.ASK_PROGRESS,
                idempotencyKey,
                ActionTarget.repairCase(repairCaseId),
                null,
                null,
                null,
                Map.of()),
            store.askingProgress(),
            rules);
    return new ProgressAsked(
        repairCaseId, rules.nextProgressAskAt(result.state()), result.action().replayed());
  }

  /**
   * 진행 상황 남기기(06 REP-6). pipeline 전에 입력을 본다: 사진이 10장을 넘거나 빈 표가 있거나 글이 2,000자를 넘으면 {@code
   * VALIDATION_FAILED}, [상황이 바뀌었어요]({@code asksCheck})는 아직 없어 {@code VALIDATION_FAILED}, 주민등록번호 형태면
   * {@code RESIDENT_ID_BLOCKED}, 글도 사진도 없으면 {@code EVIDENCE_REQUIRED}. 사진만이면 글은 빈 글자로 남는다({@code
   * progress_note.text} NOT NULL). 공유를 그만둔 사람도 남길 수 있다({@code Allow.WHILE_SHARING_STOPPED}, 03 B).
   */
  public ProgressNoteCreated noteProgress(
      UUID repairCaseId, UUID memberId, String idempotencyKey, ProgressNoteRequest request) {
    if (request == null
        || Boolean.TRUE.equals(request.asksCheck())
        || request.uploadTickets().size() > MAX_PHOTOS
        || request.uploadTickets().stream().anyMatch(t -> t == null || t.isBlank())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String text = blankToNull(request.text());
    if (text != null && text.length() > MAX_DESCRIPTION) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(text);
    AnswerSupport.requireEvidence(request.hasEvidence());
    UUID roomRecordId = roomOf(repairCaseId);
    RepairStateStore.NotingProgress noting =
        store.notingProgress(text == null ? "" : text, request.uploadTickets());
    PipelineResult<RepairState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.NOTE_PROGRESS,
                idempotencyKey,
                ActionTarget.repairCase(repairCaseId),
                null,
                null,
                null,
                Map.of()),
            noting,
            rules);
    UUID noteId =
        noting
            .createdId()
            .or(() -> notes.findIdByAction(result.action().actionId()))
            .orElseThrow(
                () -> new IllegalStateException("진행 상황이 없어요: " + result.action().actionId()));
    return new ProgressNoteCreated(noteId, result.action().replayed());
  }

  private RepairProgressChanged progress(
      UUID repairCaseId, UUID memberId, String idempotencyKey, ActionType type, String reason) {
    return progress(repairCaseId, memberId, idempotencyKey, type, reason, store.progressing());
  }

  private RepairProgressChanged progress(
      UUID repairCaseId,
      UUID memberId,
      String idempotencyKey,
      ActionType type,
      String reason,
      StateStore<RepairState> progressStore) {
    UUID roomRecordId = roomOf(repairCaseId);
    PipelineResult<RepairState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                type,
                idempotencyKey,
                ActionTarget.repairCase(repairCaseId),
                null,
                null,
                reason,
                Map.of()),
            progressStore,
            rules);
    RepairState state = result.state();
    return new RepairProgressChanged(
        repairCaseId,
        state.status(),
        state.closedBy(),
        state.defectAck(),
        result.action().replayed());
  }

  private UUID roomOf(UUID repairCaseId) {
    return cases
        .findRoomRecordIdById(repairCaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
