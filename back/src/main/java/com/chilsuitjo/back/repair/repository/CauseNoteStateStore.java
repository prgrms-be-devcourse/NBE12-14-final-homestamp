package com.chilsuitjo.back.repair.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.CauseNoteState;
import com.chilsuitjo.back.repair.rule.RepairSight;
import com.chilsuitjo.back.repair.rule.RepairState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 원인 설명을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * CauseNoteTransitions}에만).
 *
 * <p>설명은 B 테이블({@code record.cause_note})이라 고치지 않고, 고치면 이전 설명을 가리키는 새 행을 만든다(05 2-5, 06 REP-8c).
 * 글·근거 종류는 설명 행에만 두고 행동 payload에는 고칠 설명({@code previousId})과 붙인 사진만 남는다. 남기기 행동의 대상은 새 설명이고({@code
 * action.cause_note_id}), 응답 행동의 대상도 그 설명이다(05 2-5 "상대 응답은 행동 기록이고 그 행에만 붙음"). 응답은 응답 요청을 만들지
 * 않는다(문자·기다림 없음, REP-8b).
 */
@Component
@RequiredArgsConstructor
public class CauseNoteStateStore {

  /** 내용 지문의 판 종류(05 2-3 「해시 입력 형식」 내용 지문). 설명에는 판 번호가 없어 1로 둔다(보충 행과 같음). */
  static final String CONTENT_KIND = "cause_note";

  private final RepairCaseRepository cases;
  private final CauseNoteRepository notes;
  private final RepairStateStore repairs;
  private final ActionReader actions;
  private final ActionRequestContext requestContext;
  private final RepairPhotos photos;

  /**
   * 남기기·고치기(06 REP-8)의 저장 방법. 같은 키로 다시 온 요청이면 pipeline이 처음 만든 설명을 결과로 돌려준다.
   *
   * @param expertName 근거가 업체·전문가 소견일 때 업체 이름(선택). 다른 근거면 null
   * @param uploadTickets 근거 사진의 업로드 표(선택). 장수는 서비스가 먼저 본다
   */
  public StateStore<CauseNoteState> noting(
      UUID repairCaseId,
      String text,
      CauseBasis basis,
      String expertName,
      UUID previousId,
      List<String> uploadTickets) {
    return new Noting(
        Objects.requireNonNull(repairCaseId, "repairCaseId"),
        Objects.requireNonNull(text, "text"),
        Objects.requireNonNull(basis, "basis"),
        expertName,
        previousId,
        List.copyOf(uploadTickets));
  }

  /** 응답(맞아요·보류·이견)의 저장 방법. 응답은 행동 기록에만 남는다. */
  public StateStore<CauseNoteState> responding() {
    return new Responding();
  }

  /**
   * 응답할 때 본 설명의 내용 지문 재료(본 기록에 대고 하는 행동, 안내서 3-3). 설명은 고칠 수 없어 잠그기 전에 읽어도 같다. 다른 호실의 설명이면 비어 있고,
   * 그때는 pipeline이 {@code NOT_FOUND}로 막는다.
   */
  public Optional<DigestInput> seenContent(UUID causeNoteId) {
    return notes.findById(causeNoteId).map(CauseNoteStateStore::digest);
  }

  /** 설명의 호실. 없는 설명이면 {@code NOT_FOUND}. */
  public UUID roomOf(UUID causeNoteId) {
    return notes
        .findById(causeNoteId)
        .map(CauseNote::getRoomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /**
   * 이 설명의 지금 응답 상태({@code load}와 같은 값). 상세 조회가 응답 버튼을 켤지 계산할 때도 쓴다(06 4-0 "서버의 같은 함수"). 트랜잭션 안에서
   * 부른다.
   */
  public CauseNoteState responseState(CauseNote note) {
    return CauseNoteState.forResponding(
        note.getSide(), notes.existsByPreviousId(note.getId()), lastAnswer(note.getId(), null));
  }

  /**
   * 설명의 마지막 응답. 응답이 없으면 {@code PENDING}이다. {@code visibleTo}가 있으면 그 사람에게 보이는 응답만 본다(공유를 그만둔 뒤 상대가
   * 남긴 응답은 보이지 않음, 03 불변식 18).
   */
  public AckState lastAnswer(UUID causeNoteId, RepairSight visibleTo) {
    return lastResponse(causeNoteId, visibleTo)
        .map(entry -> AckState.valueOf(entry.payloadAs(AnswerPayload.class).answer().name()))
        .orElse(AckState.PENDING);
  }

  /** 설명의 마지막 응답 행동. 행동은 {@code room_seq} 순이라 마지막 것이 지금 응답이다. */
  public Optional<ActionEntry> lastResponse(UUID causeNoteId, RepairSight visibleTo) {
    ActionEntry last = null;
    for (ActionEntry entry : actions.findByTarget(target(causeNoteId))) {
      if (entry.type() == ActionType.RESPOND_CAUSE
          && (visibleTo == null || visibleTo.sees(entry.actorSide(), entry.createdAt()))) {
        last = entry;
      }
    }
    return Optional.ofNullable(last);
  }

  /** 이 사람에게 보이는 범위. 수리 건 상세와 같은 기준이다({@code RepairStateStore.sightFor}). */
  public RepairSight visibilityFor(RepairCase row, PartySide viewer) {
    return repairs.sightFor(row, viewer);
  }

  private RepairCase caseRow(UUID repairCaseId, UUID roomRecordId) {
    return cases
        .findByIdAndRoomRecordId(repairCaseId, roomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /** 행동하는 사람이 이 수리 건을 볼 수 있나(공유를 그만둔 뒤 상대가 등록한 수리 건은 볼 수 없다, 불변식 18). */
  private boolean caseVisible(RepairCase row, PartySide side) {
    RepairState state = repairs.current(row);
    return !(state.hiddenFromCounterpartKnown() && side != state.registeredBy());
  }

  /** 그 측의 지금 설명: 다음 수정본이 없는 설명(REP-8c "새 설명이 앞에"). */
  private static UUID latestOf(List<CauseNote> all, PartySide side) {
    List<UUID> replaced = new ArrayList<>();
    for (CauseNote note : all) {
      if (note.getPreviousId() != null) {
        replaced.add(note.getPreviousId());
      }
    }
    UUID latest = null;
    for (CauseNote note : all) {
      if (note.getSide() == side && !replaced.contains(note.getId())) {
        latest = note.getId();
      }
    }
    return latest;
  }

  private static ActionTarget target(UUID causeNoteId) {
    return ActionTarget.of(TargetKind.CAUSE_NOTE, causeNoteId);
  }

  /** 설명의 내용 지문 재료. 칸 순서도 형식의 일부라 바꾸면 옛 지문과 달라진다(05 2-3). */
  static DigestInput digest(CauseNote note) {
    return new DigestInput(
        CONTENT_KIND,
        note.getId(),
        1,
        List.of(
            new DigestField("text", note.getText()),
            new DigestField("basis", note.getBasis().name()),
            new DigestField("expert_name", note.getExpertName()),
            new DigestField(
                "previous_id",
                note.getPreviousId() == null ? null : note.getPreviousId().toString())));
  }

  private final class Noting implements StateStore<CauseNoteState> {

    private final UUID repairCaseId;
    private final String text;
    private final CauseBasis basis;
    private final String expertName;
    private final UUID previousId;
    private final List<String> uploadTickets;

    private Noting(
        UUID repairCaseId,
        String text,
        CauseBasis basis,
        String expertName,
        UUID previousId,
        List<String> uploadTickets) {
      this.repairCaseId = repairCaseId;
      this.text = text;
      this.basis = basis;
      this.expertName = expertName;
      this.previousId = previousId;
      this.uploadTickets = uploadTickets;
    }

    /**
     * 행동하는 측의 지금 설명. 볼 수 없는 수리 건이면 {@code NOT_FOUND}가 아니라 빈 상태다({@code apply}가 {@code
     * INVALID_STATE}).
     */
    @Override
    public CauseNoteState load(ActionCommand command) {
      RepairCase row = caseRow(repairCaseId, command.roomRecordId());
      PartySide side = command.actor().side();
      if (side == null || !caseVisible(row, side)) {
        return CauseNoteState.NONE;
      }
      return CauseNoteState.forNoting(
          latestOf(notes.findByRepairCaseIdOrderByCreatedAtAscIdAsc(repairCaseId), side));
    }

    /**
     * 새 설명 한 줄을 남긴다(그 행이 이 행동의 대상이자 내용 지문). 시각은 행동 기록과 같은 {@code at()}이다. 근거 사진은 설명을 DB에 내보낸 뒤 그
     * 설명에 붙이고(사진 행이 설명을 가리키므로), 지문은 행동 payload {@code photos}로 남는다. 사진이 실패하면 설명도 함께 되돌려진다. 상대가 물어 둔
     * 진행 상황 묻기는 이 설명으로 닫힌다(06 REP-6c).
     */
    @Override
    public SavedState save(ActionCommand command, CauseNoteState next) {
      repairs.answerProgressCheck(repairCaseId, command.actor().side());
      CauseNote note =
          notes.saveAndFlush(
              new CauseNote(
                  command.roomRecordId(),
                  repairCaseId,
                  command.actor().side(),
                  text,
                  basis,
                  expertName,
                  previousId,
                  requestContext.at()));
      return new SavedState(target(note.getId()), digest(note))
          .withPhotos(photos.attachToCause(command, repairCaseId, note.getId(), uploadTickets));
    }
  }

  private final class Responding implements StateStore<CauseNoteState> {

    /**
     * 응답할 설명의 지금 상태. 다른 호실의 설명, 볼 수 없는 수리 건의 설명, 공유를 그만둔 뒤 상대가 남겨 보이지 않는 설명은 {@code
     * NOT_FOUND}다(있는지도 알 수 없게, ROOM-2a).
     */
    @Override
    public CauseNoteState load(ActionCommand command) {
      ActionTarget target = command.target();
      if (target == null || target.kind() != TargetKind.CAUSE_NOTE) {
        throw new IllegalArgumentException("원인 설명이 아닌 대상이에요: " + target);
      }
      CauseNote note =
          notes
              .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
      RepairCase row = caseRow(note.getRepairCaseId(), command.roomRecordId());
      PartySide side = command.actor().side();
      if (side != null
          && (!caseVisible(row, side)
              || !visibilityFor(row, side).sees(note.getSide(), note.getCreatedAt()))) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      return responseState(note);
    }

    /** 응답은 행동 기록에만 남는다. 상대가 물어 둔 부모 수리 건의 진행 상황 묻기는 이 응답으로 닫힌다(06 REP-6c). */
    @Override
    public SavedState save(ActionCommand command, CauseNoteState next) {
      notes
          .findById(command.target().id())
          .ifPresent(
              note -> repairs.answerProgressCheck(note.getRepairCaseId(), command.actor().side()));
      return SavedState.unchanged();
    }
  }
}
