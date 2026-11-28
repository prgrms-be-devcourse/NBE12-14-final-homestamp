package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.dto.InspectionDetail;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionItemStateStore;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionSight;
import com.chilsuitjo.back.inspection.repository.InspectionStateStore;
import com.chilsuitjo.back.inspection.rule.ConfirmAllTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.repair.dto.RepairCaseSummary;
import com.chilsuitjo.back.repair.service.RepairListService;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점검 조회(06 4-7). 상태를 바꾸지 않아 pipeline을 거치지 않고 읽기 트랜잭션을 쓴다(09 3-1 "트랜잭션 경계"). 행동은 {@link
 * InspectionService}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class InspectionQueryService {

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final RepairListService repairs;
  private final PhotoService photos;
  private final RoomAccessService access;
  private final InspectionStateStore store;
  private final InspectionItemStateStore itemStore;
  private final InspectionSight sight;
  private final CounterpartReader parties;
  private final ActionReader actionReader;
  private final InspectionTransitions rules;
  private final InspectionItemTransitions itemRules;
  private final Clock clock;

  /**
   * 점검 하나(06 4-7, S-14). 없는 ID, 그 호실 당사자가 아님, 공유를 그만둔 뒤 상대가 시작해 볼 수 없는 점검(불변식 18)은 모두 {@code
   * NOT_FOUND}다(있는지도 알 수 없게, ROOM-2a).
   *
   * <p>보이는 상태와 항목은 {@link InspectionSight}가 정한다: <b>작성 중</b>이면 기준 측이 아닌 사람에게 기준 측이 쓴 항목은 가리고 자기가 더한
   * 항목만 준다(06 INSP-1a, #286), 그 밖에는 보는 사람 자신이 더한 항목과 공유를 그만둔 시각까지 상대가 더한 항목을 준다(불변식 18). 항목 응답·모두
   * 확인도 같은 판단을 쓴다.
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단으로 만든다: 호실 단위 거절은 {@code RoomGate.rejection}, 점검 규칙은 각
   * Transitions의 판단.
   */
  @Transactional(readOnly = true)
  public InspectionDetail detail(UUID inspectionId, UUID memberId) {
    UUID roomRecordId =
        inspections
            .findRoomRecordIdById(inspectionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomGate gate = gates.read(roomRecordId, Actor.member(memberId));
    Inspection row =
        inspections
            .findByIdAndRoomRecordId(inspectionId, roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    PartySide viewer = gate.side();
    Loaded loaded = load(row, viewer);
    InspectionSight.Sight seen = loaded.seen();
    if (seen.hides(row)) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    List<InspectionDetail.Item> shownItems = visibleItems(row, memberId, gate, loaded);
    InspectionState now = store.current(row);
    InspectionDetail.SideBySide sideBySide =
        row.getKind() == InspectionKind.MOVE_OUT
            ? sideBySide(roomRecordId, memberId, gate, shownItems)
            : null;
    // 보이는 범위를 고정한 시각 뒤에 잠겼으면 이 사람에게는 잠기기 전 모습이라 잠긴 시각·이유도 보이지 않는다(불변식 18)
    boolean shownLocked = seen.shownStatus() == InspectionStatus.LOCKED;
    return new InspectionDetail(
        row.getId(),
        roomRecordId,
        row.getKind(),
        seen.shownStatus(),
        viewer,
        row.getAuthorSide(),
        row.isLate(),
        shownLocked ? row.getLockedAt() : null,
        shownLocked ? row.getLockReason() : null,
        row.getCreatedAt(),
        seen.asOf(),
        shownItems,
        rules.counterpartCanRequestAt(now),
        actions(row, now, gate, seen),
        sideBySide);
  }

  /**
   * 퇴거 점검 나란히 보기(06 INSP-3b). 같은 부위의 입주 점검 항목 · 수리 건 · 퇴거 점검 항목을 부위마다 모은다. 각 기록은 그 기록의 상세와 같은 판단으로
   * 보인다: 입주 항목은 입주 점검 상세의 {@code items}({@link InspectionSight}), 수리 건은 수리 건 목록과 같은 계산({@code
   * RepairListService.summaries}), 퇴거 항목은 이 상세의 {@code items}. 입주 점검이 없거나 이 사람이 볼 수 없으면 입주 칸은 비어
   * 있다(화면은 "입주 점검에 기록 없음", 03 H).
   *
   * <p>표시는 기록이 있다는 사실만 본다: 입주 항목이 있으면 "입주 기존 하자", 없고 수리 건이 있으면 "거주 중 하자", 퇴거 항목만 있으면 표시하지 않는다(03 원칙
   * 3 판정하지 않음). 부위 사진은 그 기능({@code /part-photos})을 만들 때 더한다.
   */
  private InspectionDetail.SideBySide sideBySide(
      UUID roomRecordId, UUID memberId, RoomGate gate, List<InspectionDetail.Item> moveOutItems) {
    InspectionDetail.MoveIn moveIn = null;
    List<InspectionDetail.Item> moveInItems = List.of();
    Optional<Inspection> moveInRow =
        inspections.findByRoomRecordIdAndKind(roomRecordId, InspectionKind.MOVE_IN);
    if (moveInRow.isPresent()) {
      Inspection mi = moveInRow.get();
      Loaded loaded = load(mi, gate.side());
      InspectionSight.Sight seen = loaded.seen();
      if (!seen.hides(mi)) {
        moveIn =
            new InspectionDetail.MoveIn(
                mi.getId(),
                seen.shownStatus(),
                seen.shownStatus() == InspectionStatus.LOCKED ? mi.getLockedAt() : null,
                mi.isLate(),
                mi.getCreatedAt());
        moveInItems = visibleItems(mi, memberId, gate, loaded);
      }
    }
    List<RepairCaseSummary> repairCases = repairs.summaries(roomRecordId, memberId);

    Map<UUID, PartRows> byPart = new LinkedHashMap<>();
    for (InspectionDetail.Item item : moveOutItems) {
      partRows(byPart, item.version().roomPartId(), item.version().partName())
          .moveOut
          .add(item.id());
    }
    for (InspectionDetail.Item item : moveInItems) {
      partRows(byPart, item.version().roomPartId(), item.version().partName()).moveIn.add(item);
    }
    for (RepairCaseSummary repair : repairCases) {
      partRows(byPart, repair.roomPartId(), repair.partName()).repairs.add(repair);
    }
    List<InspectionDetail.Part> parts = new ArrayList<>();
    byPart.forEach((partId, rows) -> parts.add(rows.toPart(partId)));
    return new InspectionDetail.SideBySide(moveIn, List.copyOf(parts));
  }

  private static PartRows partRows(Map<UUID, PartRows> byPart, UUID partId, String partName) {
    return byPart.computeIfAbsent(partId, id -> new PartRows(partName));
  }

  /** 나란히 보기에서 부위 하나에 모으는 중인 기록. */
  private static final class PartRows {
    private final String partName;
    private final List<InspectionDetail.Item> moveIn = new ArrayList<>();
    private final List<RepairCaseSummary> repairs = new ArrayList<>();
    private final List<UUID> moveOut = new ArrayList<>();

    private PartRows(String partName) {
      this.partName = partName;
    }

    private InspectionDetail.Part toPart(UUID partId) {
      InspectionDetail.SideMark mark =
          !moveIn.isEmpty()
              ? InspectionDetail.SideMark.PREEXISTING
              : !repairs.isEmpty() ? InspectionDetail.SideMark.DURING_STAY : null;
      return new InspectionDetail.Part(
          partId, partName, mark, List.copyOf(moveIn), List.copyOf(repairs), List.copyOf(moveOut));
    }
  }

  /**
   * 점검 하나를 보이는 범위와 함께 읽는다. 자리 사실·항목·행동 기록(점검과 모든 항목 한 번)을 한 번씩 읽어 보이는 범위와 항목 응답이 함께 쓴다(링크 조회와 같음,
   * #396).
   */
  private Loaded load(Inspection row, PartySide viewer) {
    RoomParties room = parties.read(row.getRoomRecordId(), viewer);
    List<InspectionItem> all = items.findByInspectionIdOrderByIdAsc(row.getId());
    List<ActionTarget> targets = new ArrayList<>();
    targets.add(ActionTarget.inspection(row.getId()));
    all.forEach(item -> targets.add(ActionTarget.of(TargetKind.INSPECTION_ITEM, item.getId())));
    List<ActionEntry> recorded = actionReader.findByTargets(row.getRoomRecordId(), targets);
    return new Loaded(sight.of(row, viewer, room, recorded), all, recorded);
  }

  /** {@link #load}로 읽은 점검 하나. */
  private record Loaded(
      InspectionSight.Sight seen, List<InspectionItem> all, List<ActionEntry> recorded) {}

  /**
   * 이 사람에게 보이는 항목. 응답·판·다음 보충 시각은 보이는 범위({@link InspectionSight#items})로 주어 공유를 그만둔 뒤 상대가 답하거나 보충한
   * 것이 드러나지 않는다(불변식 18, #396). 판·부위는 한 번에 읽어 항목 수와 상관없이 읽는 횟수가 같다.
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단이라 지금 상태로 계산한다({@link #actions}와 같음). 보이는 값과 지금 값이 다른
   * 사람은 공유를 그만둔 사람뿐이고(상대는 그만둔 사람이 행동하지 못해 같다), 그 사람은 점검 행동이 모두 {@code SHARING_STOPPED}라 버튼이 없어 드러나는
   * 값이 없다.
   */
  private List<InspectionDetail.Item> visibleItems(
      Inspection row, UUID memberId, RoomGate gate, Loaded loaded) {
    InspectionSight.Sight seen = loaded.seen();
    List<InspectionItem> shown = loaded.all().stream().filter(seen::sees).toList();
    if (shown.isEmpty()) {
      return List.of();
    }
    UUID roomRecordId = row.getRoomRecordId();
    Map<UUID, InspectionSight.ItemSight> itemSights = sight.items(shown, seen, loaded.recorded());
    Map<UUID, RoomPartSummary> parts =
        rooms.findParts(
            roomRecordId,
            itemSights.values().stream().map(it -> it.version().getRoomPartId()).toList());
    List<PhotoTarget> targets =
        shown.stream()
            .<PhotoTarget>map(
                item -> new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()))
            .toList();
    Map<PhotoTarget, List<PhotoCard>> cards =
        photos.cards(access.require(roomRecordId, memberId), targets);
    Map<UUID, Instant> lastSupplements = lastSupplements(loaded.recorded());
    // 점검 확인 요청을 열 수 있나는 항목을 더한 측마다 같아 측마다 한 번만 읽는다
    Map<PartySide, Boolean> canOpen = new EnumMap<>(PartySide.class);
    Instant at = clock.instant();
    List<InspectionDetail.Item> result = new ArrayList<>();
    for (InspectionItem item : shown) {
      InspectionSight.ItemSight itemSight = itemSights.get(item.getId());
      InspectionItemVersion version = itemSight.version();
      // 판 번호는 할 수 있는 행동 판단에 쓰이지 않는다(본 판 확인은 요청 때 한다)
      InspectionItemState now =
          itemStore.current(
              row,
              item,
              seen,
              version.getNo(),
              lastSupplements.get(item.getId()),
              canOpen.computeIfAbsent(
                  item.getAddedBySide(), side -> sight.canOpenConfirm(roomRecordId, side)));
      InspectionItemState shownState =
          now.withStatus(itemSight.status()).withLastRerequestAt(itemSight.lastSupplementAt());
      result.add(
          new InspectionDetail.Item(
              item.getId(),
              itemSight.status(),
              item.getAddedBySide(),
              item.isAddedAfterLock(),
              item.isAddedAfterEnd(),
              item.getCreatedAt(),
              new InspectionDetail.Version(
                  version.getNo(),
                  version.getRoomPartId(),
                  parts.get(version.getRoomPartId()).name(),
                  version.getDescription()),
              cards.getOrDefault(
                  new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()), List.of()),
              itemRules.nextRerequestAt(shownState),
              itemActions(now, gate, at)));
    }
    return List.copyOf(result);
  }

  /** 항목 ID → 기록된 마지막 보충 시각(행동 기록이 원본, 안내서 8-1). */
  private static Map<UUID, Instant> lastSupplements(List<ActionEntry> recorded) {
    Map<UUID, Instant> last = new HashMap<>();
    for (ActionEntry entry : recorded) {
      if (entry.type() == ActionType.SUPPLEMENT_ITEM
          && entry.target() != null
          && entry.target().kind() == TargetKind.INSPECTION_ITEM) {
        last.merge(entry.target().id(), entry.createdAt(), (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return last;
  }

  /** 이 항목에 지금 누를 수 있는 행동. 실제 요청과 같은 판단이다(규칙 + 호실 단위 거절). */
  private List<String> itemActions(InspectionItemState state, RoomGate gate, Instant at) {
    List<String> result = new ArrayList<>();
    if (InspectionItemTransitions.canRespond(state, gate.side())
        && gate.rejection(ActionType.RESPOND_ITEM).isEmpty()) {
      result.add("respond");
    }
    if (itemRules.canSupplement(state, gate.side(), at)
        && gate.rejection(ActionType.SUPPLEMENT_ITEM).isEmpty()) {
      result.add("supplement");
    }
    return List.copyOf(result);
  }

  /**
   * 지금 이 사람이 누를 수 있는 행동. 실제 요청과 같아야 해서 보이는 범위를 고정한 상태가 아니라 지금 상태로 계산한다(수리 건 상세와 같음): 그만둔 쪽은
   * pipeline이 막아({@code SHARING_STOPPED}) 버튼이 없다.
   */
  private List<String> actions(
      Inspection row, InspectionState now, RoomGate gate, InspectionSight.Sight seen) {
    List<String> result = new ArrayList<>();
    if (InspectionItemTransitions.canAdd(row.getStatus(), gate.side())
        && gate.rejection(ActionType.ADD_ITEM).isEmpty()) {
      result.add("items");
    }
    if (rules.canRequestConfirm(now, gate.side(), clock.instant())
        && gate.rejection(ActionType.REQUEST_INSPECTION_CONFIRM).isEmpty()) {
      result.add("request-confirm");
    }
    if (gate.rejection(ActionType.CONFIRM_ALL).isEmpty()
        && gate.rejection(ActionType.RESPOND_ITEM).isEmpty()
        && ConfirmAllTransitions.canConfirmAll(
            itemStore.confirmAll(row, gate.side(), seen), gate.side())) {
      result.add("confirm-all");
    }
    if (gate.rejection(ActionType.LOCK).isEmpty()
        && rules.canLockAlone(store.currentForLockAlone(row, gate.side()), gate.side())) {
      result.add("lock-alone");
    }
    return List.copyOf(result);
  }
}
