package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.ProgressNoteResponse;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.SituationState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상황 변화 응답을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}, 06 REP-6d). 규칙은 검사하지 않는다(규칙은
 * {@code SituationTransitions}에만).
 *
 * <p>응답 행동의 대상은 진행 상황이다({@code action.progress_note_id}). 지금 답은 C 행 {@code progress_note_response}
 * 하나에 두고(바뀌면 그 행을 고침), 바뀐 이력은 행동 기록에 남는다(05 2-5).
 */
@Component
@RequiredArgsConstructor
public class SituationStateStore {

  private final ProgressNoteRepository notes;
  private final ProgressNoteResponseRepository responses;
  private final RepairCaseRepository cases;
  private final RepairStateStore repairs;
  private final ActionRequestContext requestContext;
  private final WaitService waits;

  /** 응답(맞아요·보류·이견)의 저장 방법. */
  public StateStore<SituationState> responding() {
    return new Responding();
  }

  /** 진행 상황의 호실. 없는 진행 상황이면 {@code NOT_FOUND}. */
  public UUID roomOf(UUID progressNoteId) {
    return notes
        .findById(progressNoteId)
        .map(ProgressNote::getRoomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /**
   * 응답할 때 본 진행 상황의 내용 지문 재료(본 기록에 대고 하는 행동, 안내서 3-3). 진행 상황은 고칠 수 없어 잠그기 전에 읽어도 같다. 다른 호실이면 비어 있고,
   * 그때는 pipeline이 {@code NOT_FOUND}로 막는다.
   */
  public Optional<DigestInput> seenContent(UUID progressNoteId) {
    return notes.findById(progressNoteId).map(RepairStateStore::progressDigest);
  }

  /** 이 진행 상황의 지금 응답 상태. 답이 없으면 {@code PENDING}. */
  SituationState state(ProgressNote note) {
    AckState answer =
        responses
            .findByProgressNoteId(note.getId())
            .map(row -> AckState.valueOf(row.getAnswer().name()))
            .orElse(AckState.PENDING);
    return new SituationState(true, note.getSide(), note.isAsksCheck(), answer);
  }

  /**
   * 행동하는 사람이 이 진행 상황을 볼 수 있나(03 불변식 18): 공유를 그만둔 뒤 상대가 등록한 수리 건이 아니고, 공유를 그만둔 시각 뒤에 상대가 남긴 진행 상황도
   * 아니다. 기준은 수리 건 상세와 같다({@code RepairStateStore.sharingCutoff}).
   */
  private boolean visible(RepairCase row, ProgressNote note, PartySide side) {
    RepairState state = repairs.current(row);
    if (state.hiddenFromCounterpartKnown() && side != state.registeredBy()) {
      return false;
    }
    Instant cutoff = repairs.sharingCutoff(row, side).orElse(null);
    return cutoff == null || note.getSide() == side || !note.getCreatedAt().isAfter(cutoff);
  }

  private final class Responding implements StateStore<SituationState> {

    /**
     * 응답할 진행 상황의 지금 상태. 다른 호실의 진행 상황, 행동하는 사람에게 보이지 않는 진행 상황은 {@code NOT_FOUND}다(있는지도 알 수 없게,
     * ROOM-2a).
     */
    @Override
    public SituationState load(ActionCommand command) {
      ActionTarget target = command.target();
      if (target == null || target.kind() != TargetKind.PROGRESS_NOTE) {
        throw new IllegalArgumentException("진행 상황이 아닌 대상이에요: " + target);
      }
      ProgressNote note =
          notes
              .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      RepairCase row =
          cases
              .findByIdAndRoomRecordId(note.getRepairCaseId(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      PartySide side = command.actor().side();
      if (side != null && !visible(row, note, side)) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      return state(note);
    }

    /**
     * 지금 답을 남기거나 바꾸고(사유는 행동 기록의 {@code reason}과 같은 값), 상황 변화 확인 요청을 "답함"으로 닫는다. 늦은 답이면
     * notification이 아무것도 하지 않는다({@code WaitService#answered}). 상대가 물어 둔 부모 수리 건의 진행 상황 묻기도 이 응답으로
     * 닫힌다(06 REP-6c). 진행 상황을 남긴 측에게 가는 서비스 안 알림은 알리기 PR에서 붙인다.
     */
    @Override
    public SavedState save(ActionCommand command, SituationState next) {
      UUID noteId = command.target().id();
      PartySide side = command.actor().side();
      Answer answer = Answer.valueOf(next.answer().name());
      String reason = command.reason();
      responses
          .findByProgressNoteId(noteId)
          .ifPresentOrElse(
              row -> row.answerAgain(answer, reason),
              () ->
                  responses.save(
                      new ProgressNoteResponse(
                          command.roomRecordId(),
                          noteId,
                          side,
                          answer,
                          reason,
                          requestContext.at())));
      waits.answered(
          RequestTarget.of(TargetKind.PROGRESS_NOTE, noteId), RequestKind.SITUATION_CHECK, side);
      notes
          .findById(noteId)
          .ifPresent(note -> repairs.answerProgressCheck(note.getRepairCaseId(), side));
      return SavedState.unchanged();
    }
  }
}
