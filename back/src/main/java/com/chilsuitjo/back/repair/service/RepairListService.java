package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.correction.service.CorrectionQueryService;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.service.ProposalService;
import com.chilsuitjo.back.repair.dto.RepairCaseList;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.CauseNoteRepository;
import com.chilsuitjo.back.repair.repository.ProgressNoteRepository;
import com.chilsuitjo.back.repair.repository.RepairAgendaHeadStore;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairSight;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호실의 수리 건 목록(06 4-6 {@code GET /api/rooms/{id}/repair-cases}, S-06 수리 탭). 읽기만 한다. 줄의 값은 상세({@link
 * RepairQueryService#detail})와 같은 판단으로 만든다.
 */
@Service
@RequiredArgsConstructor
public class RepairListService {

  /** 한 쪽의 기본 크기(06 4-0 "목록", {@code GET /api/rooms}와 같음). */
  public static final int DEFAULT_SIZE = 20;

  /** 한 쪽의 최대 크기. */
  public static final int MAX_SIZE = 100;

  /** 숨는 기록이 없을 때 쿼리에 넘기는 아주 먼 시각(모든 상대 측 기록이 보임). */
  private static final Instant NOTHING_HIDDEN = Instant.parse("9999-12-31T23:59:59Z");

  private final RepairCaseRepository cases;
  private final RepairContentRepository contents;
  private final CauseNoteRepository causeNotes;
  private final ProgressNoteRepository progressNotes;
  private final RepairStateStore store;
  private final RepairTransitions rules;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final ActionReader actionReader;
  private final CorrectionQueryService corrections;
  private final ProposalService proposalService;
  private final WaitQuery waits;

  /**
   * 이 호실의 수리 건, 새것부터 {@code size}개. 남의 호실은 {@code NOT_FOUND}(ROOM-2a), 틀린 커서·크기는 {@code
   * VALIDATION_FAILED}다. 보는 사람에게 보이지 않는 수리 건(상세가 {@code NOT_FOUND}인 건)의 ID를 커서로 줘도 틀린 커서와 같은 {@code
   * VALIDATION_FAILED}다(있는지 드러나지 않게, #289 리뷰).
   *
   * <p><b>보이는 범위는 상세와 같다</b>(03 6-6 9번, 불변식 18, {@link RepairSight}). 공유를 그만둔 뒤(어느 쪽이든, 제한이 살아 있을
   * 때)에는 그 시각을 {@code asOf}로 주고, 줄의 상태는 그 시각까지의 행동과 보는 사람 자신의 행동으로 다시 계산한다({@code Replay.fold}, 고친
   * 내용 응답 포함). 부담 상태는 돈 안건 장치가 같은 범위로 다시 계산한다({@code ProposalService.statusAsSeen}), 부위 이름은 부위 목록과
   * 같은 규칙이다({@code findPartAsSeenBy}). 상대가 탈퇴했으면 상세처럼 다시 계산하지 않고({@code asOf} null) 탈퇴자가 중단 동안 남긴
   * 행동만 {@code lastActionAt}에서 뺀다. 상세가 {@code NOT_FOUND}인 건(공유 중단 뒤 상대가 등록)은 쿼리에서 빼서 쪽 크기가 줄지 않는다.
   * 꺼 둔 설비·가전의 건은 그대로 보인다.
   *
   * <p>행동은 이 쪽의 수리 건과 그 진행 상황·원인 설명·고친 내용이 대상인 것만 한 번에 읽는다(#289 리뷰: 호실 전체를 읽지 않음).
   */
  @Transactional(readOnly = true)
  public RepairCaseList list(UUID roomRecordId, UUID memberId, String cursor, int size) {
    if (size < 1 || size > MAX_SIZE) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    PartySide viewer = gate.side();
    RepairSight sight = store.sightFor(roomRecordId, viewer);
    Instant asOf = sight.asOf();
    Instant hiddenAfter =
        store.hiddenAfter(roomRecordId, counterpartOf(viewer)).orElse(NOTHING_HIDDEN);

    Limit limit = Limit.of(size + 1);
    List<RepairCase> page =
        cursor == null
            ? cases.findVisible(roomRecordId, viewer, hiddenAfter, limit)
            : afterCursor(roomRecordId, viewer, hiddenAfter, cursor, limit);
    boolean more = page.size() > size;
    List<RepairCase> shown = more ? page.subList(0, size) : page;
    if (shown.isEmpty()) {
      return new RepairCaseList(asOf, viewer, List.of(), null);
    }

    List<UUID> caseIds = shown.stream().map(RepairCase::getId).toList();
    List<ProgressNote> notesOfPage = progressNotes.findByRepairCaseIdIn(caseIds);
    Map<UUID, List<ActionEntry>> historyByCase = historyByCase(roomRecordId, caseIds, notesOfPage);
    Map<UUID, List<RepairCaseList.Waiting>> waitingByCase =
        asOf == null ? waitingByCase(roomRecordId, caseIds, notesOfPage) : Map.of();
    Map<UUID, Map<Integer, RepairContent>> contentsByCase = new HashMap<>();
    for (RepairContent content :
        contents.findByRepairCaseIdIn(shown.stream().map(RepairCase::getId).toList())) {
      contentsByCase
          .computeIfAbsent(content.getRepairCaseId(), id -> new HashMap<>())
          .put(content.getNo(), content);
    }
    Map<UUID, String> partNames = new HashMap<>();

    List<RepairCaseList.Item> items = new ArrayList<>();
    for (RepairCase row : shown) {
      List<ActionEntry> visible =
          historyByCase.getOrDefault(row.getId(), List.of()).stream()
              .filter(entry -> sight.sees(entry.actorSide(), entry.createdAt()))
              .toList();
      items.add(
          item(
              row,
              visible,
              sight,
              memberId,
              contentsByCase.get(row.getId()),
              partNames,
              waitingByCase.getOrDefault(row.getId(), List.of())));
    }
    return new RepairCaseList(
        asOf, viewer, List.copyOf(items), more ? shown.getLast().getId().toString() : null);
  }

  private RepairCaseList.Item item(
      RepairCase row,
      List<ActionEntry> visible,
      RepairSight sight,
      UUID memberId,
      Map<Integer, RepairContent> contentsOfCase,
      Map<UUID, String> partNames,
      List<RepairCaseList.Waiting> waiting) {
    List<ActionEntry> caseActions =
        visible.stream().filter(entry -> isRepairCaseTarget(entry, row.getId())).toList();
    // 고정되지 않았으면 상세처럼 상태 행이 원본이고, 고정됐으면 보이는 행동으로 다시 계산한다.
    // 고친 내용 맞아요(대상이 고친 내용)가 내용의 새 판을 만들어서 함께 쌓는다(상세 visibleActions와 같음, #287·#289 리뷰)
    RepairState replayed =
        sight.asOf() == null
            ? null
            : Replay.fold(
                rules,
                visible.stream()
                    .filter(entry ->
                        isRepairCaseTarget(entry, row.getId())
                            || entry.type() == ActionType.RESPOND_CORRECTION)
                    .toList());
    int contentNo = replayed == null ? currentNo(row, contentsOfCase) : replayed.contentNo();
    RepairContent content = contentsOfCase == null ? null : contentsOfCase.get(contentNo);
    if (content == null) {
      throw new IllegalStateException("보이는 판이 없어요: " + row.getId());
    }
    String partName =
        partNames.computeIfAbsent(
            content.getRoomPartId(),
            partId -> rooms.findPartAsSeenBy(row.getRoomRecordId(), partId, memberId).name());
    Instant lastActionAt =
        visible.stream()
            .filter(entry -> entry.actorSide() != null)
            .map(ActionEntry::createdAt)
            .max(Instant::compareTo)
            .orElse(row.getCreatedAt());
    return new RepairCaseList.Item(
        row.getId(),
        row.getCreatedBySide(),
        row.getCreatedAt(),
        new RepairCaseList.Content(content.getNo(), partName, content.getTitle()),
        replayed == null ? row.getStatus() : replayed.status(),
        lastActionAt,
        replayed == null
            ? RepairStateStore.closeRequestedBy(row, caseActions)
            : replayed.closeRequestedBy(),
        replayed == null ? row.getClosedBy() : replayed.closedBy(),
        new RepairCaseList.Defect(replayed == null ? row.getDefectAck() : replayed.defectAck()),
        new RepairCaseList.Money(
            replayed == null
                ? row.getMoneyStatus()
                : RepairAgendaHeadStore.toMoneyStatus(
                    proposalService.statusAsSeen(
                        row.getRoomRecordId(),
                        new AgendaRef(AgendaKind.REPAIR_BURDEN, row.getId()),
                        sight::sees))),
        waiting);
  }

  /**
   * 이 쪽 수리 건마다 열린 응답 요청(06 4-6 {@code waiting}). {@code WaitQuery.awaiting}은 "이 측이 답할 것"만 주므로 두 측을
   * 모두 불러 "내 차례"와 "○○ 님 확인 기다림"을 함께 담는다. 호실 단위라 종류·대상이 섞여 나와서, 대상이 그 수리 건이거나 그 건의 진행 상황인 것만 고르고 지불이
   * 대상인 금액 확인({@code AMOUNT_CONFIRM})은 뺀다(돈 쪽은 {@code money.status}·상세·홈에서). 순서는 연 시각, 같으면 종류다.
   *
   * <p>공유를 그만둔 뒤({@code asOf})에는 부르지 않고 빈 목록이다(#339): 공유 중단으로 걸려 있던 일반 확인 요청은 닫히고 그만둔 사람에게는 새 요청을
   * 만들지 않으며(03 1-4), 그 시각에 열려 있던 요청을 보여 주면 지금 누를 수 없는 버튼과 어긋난다.
   */
  private Map<UUID, List<RepairCaseList.Waiting>> waitingByCase(
      UUID roomRecordId, List<UUID> caseIds, List<ProgressNote> notesOfPage) {
    Set<UUID> pageCases = new HashSet<>(caseIds);
    Map<UUID, UUID> caseOfProgressNote = new HashMap<>();
    for (ProgressNote note : notesOfPage) {
      caseOfProgressNote.put(note.getId(), note.getRepairCaseId());
    }
    List<RequestView> open = new ArrayList<>();
    for (PartySide side : PartySide.values()) {
      open.addAll(waits.awaiting(roomRecordId, side));
    }
    open.sort(
        Comparator.comparing(RequestView::openedAt).thenComparing(view -> view.kind().name()));
    Map<UUID, List<RepairCaseList.Waiting>> byCase = new HashMap<>();
    for (RequestView view : open) {
      if (view.kind() == RequestKind.AMOUNT_CONFIRM) {
        continue;
      }
      UUID caseId =
          switch (view.target().kind()) {
            case REPAIR_CASE -> pageCases.contains(view.target().id()) ? view.target().id() : null;
            case PROGRESS_NOTE -> caseOfProgressNote.get(view.target().id());
            default -> null;
          };
      if (caseId != null) {
        byCase
            .computeIfAbsent(caseId, id -> new ArrayList<>())
            .add(
                new RepairCaseList.Waiting(
                    view.kind(),
                    view.answeringSide(),
                    view.status(),
                    view.dueAt(),
                    view.soloReason()));
      }
    }
    return byCase;
  }

  /**
   * 호실 행동을 수리 건마다 나눈다({@code room_seq} 순 그대로). 이 건의 행동은 수리 건이 대상인 행동과, 그 건의 진행 상황·원인 설명·고친 내용이 대상인
   * 행동이다({@code lastActionAt}, 03 G "멈춘 기간", decisions 2026-11-26). 수리비(제안·지불)는 돈 쪽 행동이라 넣지 않는다.
   */
  private Map<UUID, List<ActionEntry>> historyByCase(
      UUID roomRecordId, List<UUID> caseIds, List<ProgressNote> notesOfPage) {
    Set<UUID> pageCases = new HashSet<>(caseIds);
    List<ActionTarget> targets = new ArrayList<>();
    Map<UUID, UUID> caseOfNote = new HashMap<>();
    for (UUID caseId : caseIds) {
      targets.add(ActionTarget.repairCase(caseId));
    }
    // 고친 내용 응답·거두기는 대상이 고친 내용이다(고치기 자체는 대상이 수리 건)
    corrections
        .repairCaseIdsByCorrection(roomRecordId)
        .forEach((correctionId, caseId) -> {
          if (pageCases.contains(caseId)) {
            caseOfNote.put(correctionId, caseId);
            targets.add(ActionTarget.correctionRequest(correctionId));
          }
        });
    for (CauseNote note : causeNotes.findByRepairCaseIdIn(caseIds)) {
      caseOfNote.put(note.getId(), note.getRepairCaseId());
      targets.add(ActionTarget.of(TargetKind.CAUSE_NOTE, note.getId()));
    }
    for (ProgressNote note : notesOfPage) {
      caseOfNote.put(note.getId(), note.getRepairCaseId());
      targets.add(ActionTarget.of(TargetKind.PROGRESS_NOTE, note.getId()));
    }
    Map<UUID, List<ActionEntry>> byCase = new HashMap<>();
    for (ActionEntry entry : actionReader.findByTargets(roomRecordId, targets)) {
      ActionTarget target = entry.target();
      if (target == null) {
        continue;
      }
      UUID caseId =
          switch (target.kind()) {
            case REPAIR_CASE -> target.id();
            case CAUSE_NOTE, PROGRESS_NOTE, CORRECTION_REQUEST -> caseOfNote.get(target.id());
            default -> null;
          };
      if (caseId != null) {
        byCase.computeIfAbsent(caseId, id -> new ArrayList<>()).add(entry);
      }
    }
    return byCase;
  }

  private List<RepairCase> afterCursor(
      UUID roomRecordId, PartySide viewer, Instant hiddenAfter, String cursor, Limit limit) {
    RepairCase last =
        cases
            .findByIdAndRoomRecordId(parseCursor(cursor), roomRecordId)
            // 목록 쿼리와 같은 숨김: 보는 사람에게 보이지 않는 건이면 없는 커서와 같게 거절한다(#289 리뷰)
            .filter(
                row -> row.getCreatedBySide() == viewer || !row.getCreatedAt().isAfter(hiddenAfter))
            .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    return cases.findVisibleAfter(
        roomRecordId, viewer, hiddenAfter, last.getCreatedAt(), last.getId(), limit);
  }

  private static boolean isRepairCaseTarget(ActionEntry entry, UUID repairCaseId) {
    return entry.target() != null
        && entry.target().kind() == TargetKind.REPAIR_CASE
        && entry.target().id().equals(repairCaseId);
  }

  private static int currentNo(RepairCase row, Map<Integer, RepairContent> contentsOfCase) {
    if (contentsOfCase != null) {
      for (RepairContent content : contentsOfCase.values()) {
        if (content.getId().equals(row.getCurrentContentId())) {
          return content.getNo();
        }
      }
    }
    throw new IllegalStateException("지금 판이 없어요: " + row.getId());
  }

  private static PartySide counterpartOf(PartySide side) {
    return side == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
  }

  private static UUID parseCursor(String cursor) {
    try {
      return UUID.fromString(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
  }
}
