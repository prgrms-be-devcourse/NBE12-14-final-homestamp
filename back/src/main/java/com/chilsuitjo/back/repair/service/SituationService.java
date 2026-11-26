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
import com.chilsuitjo.back.repair.repository.SituationStateStore;
import com.chilsuitjo.back.repair.rule.SituationState;
import com.chilsuitjo.back.repair.rule.SituationTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 상황 변화 응답을 pipeline에 넘긴다(06 REP-6d). 트랜잭션은 pipeline이 연다(09 3-1): {@code @Transactional}을 두지 않는다.
 * [상황이 바뀌었어요]를 켜는 쪽은 진행 상황 남기기({@code RepairService.noteProgress})다.
 */
@Service
@RequiredArgsConstructor
public class SituationService {

  private final ActionPipeline pipeline;
  private final SituationStateStore store;
  private final SituationTransitions rules;

  /**
   * 상황 변화 응답(06 4-6 {@code POST /api/progress-notes/{id}/respond}). 답이 없으면 {@code
   * VALIDATION_FAILED}, 사유에 주민등록번호 형태가 있으면 {@code RESIDENT_ID_BLOCKED}(pipeline 전, 03 6-1), 없는 진행
   * 상황·당사자가 아님·볼 수 없는 진행 상황은 {@code NOT_FOUND}, [상황이 바뀌었어요]로 남긴 진행 상황이 아니면 {@code INVALID_STATE}, 그
   * 뒤는 {@code AnswerSupport.answer} 순서다(진행 상황을 남긴 측이면 {@code NOT_COUNTERPART_SIDE}, 맞아요는 끝 {@code
   * ACK_FINAL}, 보류·이견에서는 맞아요로만 {@code INVALID_STATE}, 사유 {@code REASON_REQUIRED}).
   *
   * <p>답은 payload {@code answer}, 사유는 행동 기록의 {@code reason}에 남고(#155) 내용 지문은 답한 진행 상황이다. 진행 상황은 판이
   * 하나라 {@code seenVersionNo}는 보지 않는다(원인 설명과 같음). 공유를 그만둔 사람은 pipeline이 {@code SHARING_STOPPED}로
   * 막고, 거주 종료 뒤에도 정산 완료 전까지 답한다.
   */
  public AnswerResult respond(
      UUID progressNoteId, UUID memberId, String idempotencyKey, RespondRequest request) {
    if (request == null || request.answer() == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    ResidentIdGuard.check(request.reason());
    UUID roomRecordId = store.roomOf(progressNoteId);
    PipelineResult<SituationState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.RESPOND_SITUATION,
                idempotencyKey,
                ActionTarget.of(TargetKind.PROGRESS_NOTE, progressNoteId),
                null,
                store.seenContent(progressNoteId).orElse(null),
                request.reason() == null || request.reason().isBlank() ? null : request.reason(),
                Map.of("answer", request.answer().name())),
            store.responding(),
            rules);
    return new AnswerResult(
        progressNoteId, null, result.state().answer(), result.action().replayed());
  }
}
