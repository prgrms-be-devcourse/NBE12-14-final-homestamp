package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionResult;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.correction.dto.AcceptAllRequest;
import com.chilsuitjo.back.correction.dto.AcceptAllResponse;
import com.chilsuitjo.back.correction.dto.CorrectRequest;
import com.chilsuitjo.back.correction.dto.CorrectRequest.FieldChange;
import com.chilsuitjo.back.correction.dto.CorrectResponse;
import com.chilsuitjo.back.correction.dto.CorrectableSnapshot;
import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.repository.CorrectionStateStore;
import com.chilsuitjo.back.correction.rule.CorrectionResult;
import com.chilsuitjo.back.correction.rule.CorrectionState;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.rule.CorrectionTransitions;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 고치기 행동을 pipeline에 넘긴다(03 G, 06 REP-7). 트랜잭션은 pipeline이 연다(09 3-1): {@code @Transactional}을 두지
 * 않는다.
 *
 * <p>기록마다 다른 일(판 만들기·값 확인)은 그 기록의 {@link Correctable}이 한다. correction은 도메인을 모르고 Spring이 넣어 준 구현
 * 목록에서 종류로 고른다(09 3장 "고치기").
 */
@Service
public class CorrectionService {

  /** 고친 이유 길이(하자 설명과 같음). */
  static final int MAX_REASON = 2000;

  /** 다시 요청에 더하는 사진 장수(수리 건 보충과 같음, 06 4-0 업로드). */
  static final int MAX_PHOTOS = 10;

  private final ActionPipeline pipeline;
  private final CorrectionStateStore store;
  private final CorrectionTransitions rules;
  private final Map<CorrectionTargetKind, Correctable> targets = new LinkedHashMap<>();

  CorrectionService(
      ActionPipeline pipeline,
      CorrectionStateStore store,
      CorrectionTransitions rules,
      List<Correctable> correctables) {
    this.pipeline = pipeline;
    this.store = store;
    this.rules = rules;
    for (Correctable correctable : correctables) {
      if (targets.put(correctable.kind(), correctable) != null) {
        throw new IllegalStateException("고칠 기록 종류마다 구현은 하나예요: " + correctable.kind());
      }
    }
  }

  /**
   * 고치기(06 REP-7a·7c·7h·7k). 결과는 서버가 정한다: 도장 전이면 새 판으로 바로 바뀌고({@code APPLIED}, 응답 요청 없음), 도장 뒤면
   * 앞모습은 그대로 두고 칸마다 고친 내용을 남기고 상대에게 확인 요청 하나를 보낸다({@code REQUESTED}).
   *
   * <p>pipeline 전에 입력을 본다: 대상·칸이 없거나 같은 칸이 두 번이거나 사유가 2,000자를 넘거나 아직 고칠 수 없는 기록 종류면 {@code
   * VALIDATION_FAILED}, 사유에 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 없는 기록은 {@code NOT_FOUND}. 잠근
   * 뒤에는 칸 값 확인({@code Correctable.normalize}) → {@code CorrectionTransitions} 순서다.
   *
   * <p>도장 여부는 잠그기 전에 읽어 요청 모양(행동 하나·칸마다 하나)을 정하고, 잠근 뒤 다시 봐서 그사이 바뀌었으면 {@code VERSION_CONFLICT}다.
   */
  public Corrected correct(UUID memberId, String idempotencyKey, CorrectRequest request) {
    validate(request);
    Correctable target = targetOf(request.targetKind());
    CorrectableSnapshot snapshot = target.load(request.targetId());
    String reason = blankToNull(request.reason());
    Map<String, Object> changes = new LinkedHashMap<>();
    for (FieldChange change : request.changes()) {
      changes.put(change.field(), change.newValue());
    }
    ActionBatch batch =
        ActionBatch.of(snapshot.roomRecordId(), Actor.member(memberId), idempotencyKey);
    if (!snapshot.stamped()) {
      ActionTarget record = ActionTarget.of(request.targetKind().targetKind(), request.targetId());
      batch.step(
          ActionStep.of(
                  ActionType.CORRECT,
                  done -> record,
                  store.applying(target, request.targetId(), changes, memberId),
                  rules)
              .withReason(reason)
              .withPayload(
                  Map.of(
                      "result",
                      CorrectionResult.APPLIED.name(),
                      "fields",
                      List.copyOf(changes.keySet()))));
      BatchResult result = pipeline.executeAll(batch);
      return new Corrected(CorrectionResult.APPLIED, List.of(), result.replayed());
    }
    for (Map.Entry<String, Object> change : changes.entrySet()) {
      batch.step(
          ActionStep.creating(
                  ActionType.CORRECT,
                  store.requesting(
                      target,
                      request.targetId(),
                      change.getKey(),
                      change.getValue(),
                      memberId,
                      reason),
                  rules)
              .withReason(reason)
              .withPayload(
                  Map.of("result", CorrectionResult.REQUESTED.name(), "field", change.getKey())));
    }
    BatchResult result = pipeline.executeAll(batch);
    List<UUID> ids = new ArrayList<>();
    for (ActionResult action : result.actions()) {
      ids.add(action.target().id());
    }
    return new Corrected(CorrectionResult.REQUESTED, ids, result.replayed());
  }

  /**
   * 고친 내용에 응답(06 REP-7d·7e·7i·7j·7m). 답이 없으면 {@code VALIDATION_FAILED}, 사유에 주민등록번호 형태면 {@code
   * RESIDENT_ID_BLOCKED}(pipeline 전). 없는 고친 내용·다른 호실은 {@code NOT_FOUND}, 그 뒤는 {@code
   * CorrectionTransitions} 순서다.
   *
   * <p>본 번호({@code seenVersionNo})는 화면에 보인 앞모습(도장 판)의 번호다. 행동 기록의 {@code seen_version_no}로
   * 남고(REP-7d), 지금 도장 판과 다르면 {@code VERSION_CONFLICT}다. 내용 지문은 본 판이다(안내서 3-3). 맞아요면 그 칸만 합친 새 판이
   * 앞모습이 되고 다른 고친 내용은 그대로다.
   */
  public AnswerResult respond(
      UUID correctionId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    CorrectionRequest row = store.find(correctionId);
    Correctable target = targetOf(row.targetKind());
    CorrectableSnapshot snapshot = target.load(row.targetId());
    DigestInput seen =
        snapshot.confirmedVersionId() == null
            ? null
            : target.digestOf(snapshot.confirmedVersionId());
    PipelineResult<CorrectionState> result =
        pipeline.execute(
            new ActionCommand(
                row.getRoomRecordId(),
                Actor.member(memberId),
                ActionType.RESPOND_CORRECTION,
                idempotencyKey,
                ActionTarget.correctionRequest(correctionId),
                request.seenVersionNo(),
                seen,
                blankToNull(request.reason()),
                Map.of("answer", request.answer().name())),
            store.responding(target),
            rules);
    return new AnswerResult(
        correctionId, null, result.state().answer(), result.action().replayed());
  }

  /**
   * [모두 확인](06 4-6 {@code accept-all}, 03 G "상대가 확인"): 한 기록의 확인 전 고친 내용을 한 번에 맞아요로 답한다. 고친 내용마다 응답
   * 행동 {@code RESPOND_CORRECTION} 하나를 한 묶음(트랜잭션 하나)으로 남기므로 하나라도 거절되면 아무것도 바뀌지 않는다. 본 번호와 내용 지문은 화면이
   * 본 앞모습이다(REP-7d). 마지막 고친 내용까지 답하면 확인 요청은 답함으로 닫힌다.
   *
   * <p>pipeline 전에 입력을 본다: 기록·본 번호·고친 내용이 비었거나 같은 고친 내용이 두 번이면 {@code VALIDATION_FAILED}, 없는 기록은
   * {@code NOT_FOUND}. 잠근 뒤에는 화면이 본 목록이 지금 기다리는 목록과 다르면 {@code VERSION_CONFLICT}, 그 뒤는 하나씩 응답과 같다.
   */
  public BatchAnswered acceptAll(UUID memberId, String idempotencyKey, AcceptAllRequest request) {
    if (request == null
        || request.targetKind() == null
        || request.targetId() == null
        || request.seenVersionNo() == null
        || request.correctionIds() == null
        || request.correctionIds().isEmpty()
        || request.correctionIds().contains(null)
        || new HashSet<>(request.correctionIds()).size() != request.correctionIds().size()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    Correctable target = targetOf(request.targetKind());
    CorrectableSnapshot snapshot = target.load(request.targetId());
    DigestInput seen =
        snapshot.confirmedVersionId() == null
            ? null
            : target.digestOf(snapshot.confirmedVersionId());
    var store =
        this.store.acceptingAll(
            target,
            request.targetKind().targetKey(request.targetId()),
            Set.copyOf(request.correctionIds()));
    ActionBatch batch =
        ActionBatch.of(snapshot.roomRecordId(), Actor.member(memberId), idempotencyKey);
    for (UUID correctionId : request.correctionIds()) {
      batch.step(
          ActionStep.of(
                  ActionType.RESPOND_CORRECTION,
                  done -> ActionTarget.correctionRequest(correctionId),
                  store,
                  rules)
              .withSeenVersionNo(request.seenVersionNo())
              .withContent(seen)
              .withPayload(Map.of("answer", Answer.ACK.name(), "acceptAll", true)));
    }
    BatchResult result = pipeline.executeAll(batch);
    return new BatchAnswered(new AcceptAllResponse(request.correctionIds()), result.replayed());
  }

  /** 고친 내용 거두기. 고친 측만, 기다리는 고친 내용만 된다. 다시 온 요청이면 참. */
  public boolean withdraw(UUID correctionId, UUID memberId, String idempotencyKey) {
    CorrectionRequest row = store.find(correctionId);
    PipelineResult<CorrectionState> result =
        pipeline.execute(
            new ActionCommand(
                row.getRoomRecordId(),
                Actor.member(memberId),
                ActionType.WITHDRAW_CORRECTION,
                idempotencyKey,
                ActionTarget.correctionRequest(correctionId),
                null,
                null,
                null,
                Map.of()),
            store.withdrawing(),
            rules);
    return result.action().replayed();
  }

  /**
   * [사진 더해서 다시 요청](06 4-6 {@code resubmit}, 03 G "보충하고 다시 요청"): 보류·이견이 달린 고친 내용을 같은 값에 근거만 더해 다시
   * 보낸다. 새 고친 내용을 만들고({@code retry_of_id}) 그 ID를 돌려준다.
   *
   * <p>pipeline 전에 입력을 본다: 사진이 10장을 넘거나 빈 표가 있거나 설명이 2,000자를 넘으면 {@code VALIDATION_FAILED}, 주민등록번호
   * 형태면 {@code RESIDENT_ID_BLOCKED}, 사진도 설명도 없으면 {@code EVIDENCE_REQUIRED}. 잠근 뒤는 {@code
   * CorrectionTransitions} 순서다(고친 측 → 처리 완료로 닫힘 → 그 칸의 마지막·보류·이견 → 같은 기록의 다시 요청끼리 7일).
   */
  public Resubmitted resubmit(
      UUID correctionId, UUID memberId, String idempotencyKey, SupplementRequest request) {
    if (request == null
        || request.uploadTickets().size() > MAX_PHOTOS
        || request.uploadTickets().stream().anyMatch(t -> t == null || t.isBlank())) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String description = blankToNull(request.description());
    if (description != null && description.length() > MAX_REASON) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(description);
    AnswerSupport.requireEvidence(request.hasEvidence());
    CorrectionRequest row = store.find(correctionId);
    Correctable target = targetOf(row.targetKind());
    ActionBatch batch =
        ActionBatch.of(row.getRoomRecordId(), Actor.member(memberId), idempotencyKey)
            .creating(
                ActionType.RESUBMIT_CORRECTION,
                store.resubmitting(target, correctionId, description, request.uploadTickets()),
                rules);
    BatchResult result = pipeline.executeAll(batch);
    return new Resubmitted(result.actions().getFirst().target().id(), result.replayed());
  }

  /**
   * [사진 더해서 다시 요청] 결과.
   *
   * @param correctionId 새로 만든 고친 내용
   * @param replayed 같은 키로 다시 온 요청
   */
  public record Resubmitted(UUID correctionId, boolean replayed) {}

  private Correctable targetOf(CorrectionTargetKind kind) {
    Correctable target = targets.get(kind);
    if (target == null) {
      // 아직 그 기록의 고치기를 만들지 않음(지불·점검·송금·입주일은 C·A가 구현)
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return target;
  }

  private static void validate(CorrectRequest request) {
    if (request == null
        || request.targetKind() == null
        || request.targetId() == null
        || request.changes() == null
        || request.changes().isEmpty()
        || (request.reason() != null && request.reason().length() > MAX_REASON)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    Set<String> fields = new HashSet<>();
    for (FieldChange change : request.changes()) {
      if (change == null
          || change.field() == null
          || change.field().isBlank()
          || !fields.add(change.field())) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
    }
    ResidentIdGuard.check(request.reason());
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }

  /**
   * [모두 확인] 결과.
   *
   * @param replayed 같은 키로 다시 온 요청
   */
  public record BatchAnswered(AcceptAllResponse response, boolean replayed) {}

  /**
   * 고치기 결과.
   *
   * @param correctionIds 확인 요청이 된 고친 내용들. 바로 바뀌었으면 빈 목록
   * @param replayed 같은 키로 다시 온 요청
   */
  public record Corrected(CorrectionResult result, List<UUID> correctionIds, boolean replayed) {

    public CorrectResponse response() {
      return CorrectResponse.of(result, correctionIds);
    }
  }
}
