package com.chilsuitjo.back.repair.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.repair.dto.RepairCaseList;
import com.chilsuitjo.back.repair.entity.CauseNote;
import com.chilsuitjo.back.repair.entity.ProgressNote;
import com.chilsuitjo.back.repair.entity.RepairCase;
import com.chilsuitjo.back.repair.entity.RepairContent;
import com.chilsuitjo.back.repair.repository.CauseNoteRepository;
import com.chilsuitjo.back.repair.repository.ProgressNoteRepository;
import com.chilsuitjo.back.repair.repository.RepairCaseRepository;
import com.chilsuitjo.back.repair.repository.RepairContentRepository;
import com.chilsuitjo.back.repair.repository.RepairStateStore;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /**
   * 이 호실의 수리 건, 새것부터 {@code size}개. 남의 호실은 {@code NOT_FOUND}(ROOM-2a), 틀린 커서·크기는 {@code
   * VALIDATION_FAILED}다.
   *
   * <p><b>보이는 범위는 상세와 같다</b>(03 6-6 9번, 불변식 18). 공유를 그만둔 뒤(어느 쪽이든, 제한이 살아 있을 때)에는 그 시각을 {@code
   * asOf}로 주고, 줄의 상태는 그 시각까지의 행동과 보는 사람 자신의 행동으로 다시 계산한다({@code Replay.fold}). 상세가 {@code
   * NOT_FOUND}인 건(공유 중단 뒤 상대가 등록)은 쿼리에서 빼서 쪽 크기가 줄지 않는다. 꺼 둔 설비·가전의 건은 그대로 보인다.
   *
   * <p>행동은 호실 단위로 한 번 읽어 건마다 나눈다(건마다 읽으면 쪽 크기만큼 쿼리가 늘어난다).
   */
  @Transactional(readOnly = true)
  public RepairCaseList list(UUID roomRecordId, UUID memberId, String cursor, int size) {
    if (size < 1 || size > MAX_SIZE) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    PartySide viewer = gate.side();
    Instant asOf = store.sharingCutoff(roomRecordId, viewer).orElse(null);
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

    Map<UUID, List<ActionEntry>> historyByCase = historyByCase(roomRecordId);
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
              .filter(entry -> visibleTo(entry, viewer, asOf))
              .toList();
      items.add(item(row, visible, asOf, contentsByCase.get(row.getId()), partNames));
    }
    return new RepairCaseList(
        asOf, viewer, List.copyOf(items), more ? shown.getLast().getId().toString() : null);
  }

  private RepairCaseList.Item item(
      RepairCase row,
      List<ActionEntry> visible,
      Instant asOf,
      Map<Integer, RepairContent> contentsOfCase,
      Map<UUID, String> partNames) {
    List<ActionEntry> caseActions =
        visible.stream().filter(entry -> isRepairCaseTarget(entry, row.getId())).toList();
    // 고정되지 않았으면 상세처럼 상태 행이 원본이고, 고정됐으면 보이는 행동으로 다시 계산한다
    RepairState replayed = asOf == null ? null : Replay.fold(rules, caseActions);
    int contentNo = replayed == null ? currentNo(row, contentsOfCase) : replayed.contentNo();
    RepairContent content = contentsOfCase == null ? null : contentsOfCase.get(contentNo);
    if (content == null) {
      throw new IllegalStateException("보이는 판이 없어요: " + row.getId());
    }
    String partName =
        partNames.computeIfAbsent(
            content.getRoomPartId(),
            partId -> rooms.findPart(row.getRoomRecordId(), partId).name());
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
        new RepairCaseList.Money(replayed == null ? row.getMoneyStatus() : replayed.moneyStatus()),
        List.of());
  }

  /**
   * 호실 행동을 수리 건마다 나눈다({@code room_seq} 순 그대로). 이 건의 행동은 수리 건이 대상인 행동과, 그 건의 진행 상황·원인 설명이 대상인
   * 행동이다({@code lastActionAt}, 03 G "멈춘 기간"). 수리비(제안·지불)는 돈 쪽 행동이라 넣지 않는다.
   */
  private Map<UUID, List<ActionEntry>> historyByCase(UUID roomRecordId) {
    Map<UUID, UUID> caseOfNote = new HashMap<>();
    for (CauseNote note : causeNotes.findByRoomRecordId(roomRecordId)) {
      caseOfNote.put(note.getId(), note.getRepairCaseId());
    }
    for (ProgressNote note : progressNotes.findByRoomRecordId(roomRecordId)) {
      caseOfNote.put(note.getId(), note.getRepairCaseId());
    }
    Map<UUID, List<ActionEntry>> byCase = new HashMap<>();
    for (ActionEntry entry : actionReader.findByRoom(roomRecordId)) {
      ActionTarget target = entry.target();
      if (target == null) {
        continue;
      }
      UUID caseId =
          switch (target.kind()) {
            case REPAIR_CASE -> target.id();
            case CAUSE_NOTE, PROGRESS_NOTE -> caseOfNote.get(target.id());
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
            .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    return cases.findVisibleAfter(
        roomRecordId, viewer, hiddenAfter, last.getCreatedAt(), last.getId(), limit);
  }

  /** 상세의 {@code visibleActions}와 같은 규칙: 고정 시각까지의 행동과 보는 사람 측의 행동. */
  private static boolean visibleTo(ActionEntry entry, PartySide viewer, Instant asOf) {
    return asOf == null || !entry.createdAt().isAfter(asOf) || entry.actorSide() == viewer;
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
