package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ActionVisibility;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.correction.dto.CorrectionView;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.CauseNoteRepository;
import com.chilsuitjo.back.repair.repository.ProgressNoteRepository;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairSight;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RepairLinkQuery}의 진짜 구현. 수리 건·판·원인 설명·진행 상황·고친 내용과 행동 기록을 읽기만 한다.
 *
 * <p>보이는 범위는 회원 상세({@link RepairQueryService#detail})와 같은 부품을 쓴다: 수리 건 숨김은 {@code
 * RepairState.hiddenFromCounterpartKnown}, 보이는 기준 시각은 {@link RepairSight}, 상태·판은 같은 보이는 행동({@link
 * RepairQueryService#visibleActions})을 {@link Replay#fold}로 다시 계산하고, 고친 내용 응답은 {@link
 * RepairQueryService#shownAnswer}다. 경과는 경과·내려받기·점검 링크와 같은 {@link ActionVisibility#select}로 고른다.
 *
 * <p>원인 설명·진행 상황의 응답은 경과와 함께 읽은 행동 기록에서 찾아(설명마다 따로 읽지 않음) 비로그인 공개 경로의 읽는 횟수가 설명 수와 상관없다.
 */
@Service
@RequiredArgsConstructor
public class JpaRepairLinkQuery implements RepairLinkQuery {

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RepairQueryService queries;
  private final CauseNoteRepository causeNotes;
  private final ProgressNoteRepository progressNotes;
  private final CorrectionReader correctionReader;
  private final ActionReader actions;
  private final RoomQueryService rooms;

  @Override
  @Transactional(readOnly = true)
  public Optional<RepairLinkView> forLink(
      UUID roomRecordId, UUID repairCaseId, PartySide viewerSide) {
    return cases
        .findByIdAndRoomRecordId(repairCaseId, roomRecordId)
        .flatMap(row -> view(row, viewerSide));
  }

  private Optional<RepairLinkView> view(RepairCase row, PartySide viewer) {
    RepairState state = store.current(row);
    if (state.hiddenFromCounterpartKnown() && viewer != state.registeredBy()) {
      return Optional.empty();
    }
    RepairSight sight = store.sightFor(row, viewer);
    // 회원 상세와 같다: 고정 시각이 있으면 보이는 행동으로 다시 계산하고, 탈퇴(departed)면 지금 상태 그대로다(#294 리뷰)
    RepairState shown =
        sight.asOf() == null ? state : Replay.fold(rules, queries.visibleActions(row, sight));
    RepairContent content =
        contents
            .findByRepairCaseIdAndNo(row.getId(), shown.contentNo())
            .orElseThrow(() -> new IllegalStateException("보이는 판이 없어요: " + row.getId()));

    List<CorrectionView> allCorrections =
        correctionReader.findByRecord(CorrectionTargetKind.REPAIR_CASE, row.getId());
    List<CauseNote> allCauses = causeNotes.findByRepairCaseIdOrderByCreatedAtAscIdAsc(row.getId());
    List<ProgressNote> allProgress =
        progressNotes.findByRepairCaseIdOrderByCreatedAtAscIdAsc(row.getId());

    List<ActionTarget> targets = new ArrayList<>();
    targets.add(ActionTarget.repairCase(row.getId()));
    allCauses.forEach(n -> targets.add(ActionTarget.of(TargetKind.CAUSE_NOTE, n.getId())));
    allProgress.forEach(n -> targets.add(ActionTarget.of(TargetKind.PROGRESS_NOTE, n.getId())));
    allCorrections.forEach(c -> targets.add(ActionTarget.correctionRequest(c.id())));
    List<ActionEntry> recorded = actions.findByTargets(row.getRoomRecordId(), targets);

    List<CorrectionView> corrections =
        allCorrections.stream()
            .filter(c -> sight.sees(c.requestedBySide(), c.createdAt()))
            .toList();
    List<CauseNote> causes =
        allCauses.stream().filter(n -> sight.sees(n.getSide(), n.getCreatedAt())).toList();
    List<ProgressNote> progress =
        allProgress.stream().filter(n -> sight.sees(n.getSide(), n.getCreatedAt())).toList();

    Map<UUID, RoomPartSummary> parts = parts(row, content, corrections);
    Map<UUID, AckState> answers = lastAnswers(recorded, sight);
    return Optional.of(
        new RepairLinkView(
            row.getCreatedBySide(),
            shown.status(),
            shown.closeRequestedBy(),
            shown.closedBy(),
            shown.defectAck(),
            row.getCreatedAt(),
            sight.asOf(),
            parts.get(content.getRoomPartId()).name(),
            content.getTitle(),
            content.getDescription(),
            new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, row.getId()),
            corrections.stream().map(c -> correction(c, viewer, sight, parts)).toList(),
            currentCauses(causes, answers),
            progress.stream().map(n -> progressNote(n, answers)).toList(),
            history(recorded, viewer, sight, row, causes, progress, corrections)));
  }

  /** 보이는 판의 부위와 부위를 고친 내용의 새 부위. 한 번에 읽는다. */
  private Map<UUID, RoomPartSummary> parts(
      RepairCase row, RepairContent content, List<CorrectionView> corrections) {
    Set<UUID> ids = new HashSet<>();
    ids.add(content.getRoomPartId());
    for (CorrectionView c : corrections) {
      if (RepairCorrectable.ROOM_PART_ID.equals(c.field()) && c.newValue() != null) {
        ids.add(UUID.fromString(c.newValue().toString()));
      }
    }
    return rooms.findParts(row.getRoomRecordId(), ids);
  }

  private static RepairLinkView.Correction correction(
      CorrectionView view, PartySide viewer, RepairSight sight, Map<UUID, RoomPartSummary> parts) {
    RepairQueryService.Shown shown = RepairQueryService.shownAnswer(view, viewer, sight.asOf());
    String newValue;
    if (view.newValue() == null) {
      newValue = null;
    } else if (RepairCorrectable.ROOM_PART_ID.equals(view.field())) {
      newValue = parts.get(UUID.fromString(view.newValue().toString())).name();
    } else {
      newValue = view.newValue().toString();
    }
    return new RepairLinkView.Correction(
        view.id(),
        view.field(),
        newValue,
        view.requestedBySide(),
        view.reason(),
        shown.status(),
        shown.answer(),
        view.createdAt(),
        new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, view.id()));
  }

  /** 측마다 지금 설명: 보이는 설명 중 다음 수정본이 보이지 않는 것(회원 상세와 같음, REP-8c). */
  private static List<RepairLinkView.CauseNote> currentCauses(
      List<CauseNote> visible, Map<UUID, AckState> answers) {
    Set<UUID> replaced = new HashSet<>();
    for (CauseNote note : visible) {
      if (note.getPreviousId() != null) {
        replaced.add(note.getPreviousId());
      }
    }
    return visible.stream()
        .filter(note -> !replaced.contains(note.getId()))
        .map(note ->
            new RepairLinkView.CauseNote(
                note.getId(),
                note.getSide(),
                note.getText(),
                note.getBasis(),
                note.getCreatedAt(),
                answers.getOrDefault(note.getId(), AckState.PENDING),
                new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, note.getId())))
        .toList();
  }

  private static RepairLinkView.ProgressNote progressNote(
      ProgressNote note, Map<UUID, AckState> answers) {
    return new RepairLinkView.ProgressNote(
        note.getId(),
        note.getSide(),
        note.getText(),
        note.isAsksCheck(),
        note.getCreatedAt(),
        note.isAsksCheck() ? answers.getOrDefault(note.getId(), AckState.PENDING) : null,
        new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, note.getId()));
  }

  /**
   * 원인 설명·진행 상황마다 보이는 마지막 응답({@code CauseNoteStateStore.lastResponse}·{@code
   * SituationStateStore.lastResponse}와 같은 기준). 행동 기록은 읽은 순서({@code room_seq})라 뒤의 것이 마지막이다.
   */
  private static Map<UUID, AckState> lastAnswers(List<ActionEntry> recorded, RepairSight sight) {
    Map<UUID, AckState> last = new HashMap<>();
    for (ActionEntry entry : recorded) {
      if ((entry.type() == ActionType.RESPOND_CAUSE || entry.type() == ActionType.RESPOND_SITUATION)
          && entry.target() != null
          && sight.sees(entry.actorSide(), entry.createdAt())) {
        last.put(
            entry.target().id(),
            AckState.valueOf(entry.payloadAs(AnswerPayload.class).answer().name()));
      }
    }
    return last;
  }

  /**
   * 이 수리 건과 보이는 원인 설명(고치기 전 것 포함)·진행 상황·고친 내용의 행동 가운데 이 측에게 보이는 것. 고르는 기준은 경과·내려받기와 같다({@link
   * ActionVisibility#select}: 공유 중단 기준 시각 뒤 상대·서버 행동, 통지 상태를 뺀다). 기준 시각은 {@link RepairSight}와 같은
   * 값이다.
   */
  private static List<RepairLinkView.History> history(
      List<ActionEntry> recorded,
      PartySide viewer,
      RepairSight sight,
      RepairCase row,
      List<CauseNote> causes,
      List<ProgressNote> progress,
      List<CorrectionView> corrections) {
    Set<UUID> shownIds = new HashSet<>();
    shownIds.add(row.getId());
    causes.forEach(n -> shownIds.add(n.getId()));
    progress.forEach(n -> shownIds.add(n.getId()));
    corrections.forEach(c -> shownIds.add(c.id()));
    ActionVisibility.Cutoff cutoff = new ActionVisibility.Cutoff(sight.shared(), sight.departed());
    return ActionVisibility.select(recorded, viewer, cutoff).stream()
        .filter(entry -> entry.target() != null && shownIds.contains(entry.target().id()))
        .map(entry ->
            new RepairLinkView.History(
                entry.type(),
                entry.actorSide(),
                entry.createdAt(),
                entry.target().kind(),
                entry.target().kind() == TargetKind.REPAIR_CASE ? null : entry.target().id()))
        .toList();
  }
}
