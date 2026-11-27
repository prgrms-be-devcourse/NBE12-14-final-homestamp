package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.dto.InspectionDetail;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionItemStateStore;
import com.chilsuitjo.back.inspection.repository.InspectionItemVersionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionSight;
import com.chilsuitjo.back.inspection.repository.InspectionStateStore;
import com.chilsuitjo.back.inspection.rule.ConfirmAllTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.repair.dto.RepairCaseSummary;
import com.chilsuitjo.back.repair.service.RepairListService;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
  private final InspectionItemVersionRepository versions;
  private final RoomGateReader gates;
  private final RoomQueryService rooms;
  private final RepairListService repairs;
  private final PhotoService photos;
  private final RoomAccessService access;
  private final InspectionStateStore store;
  private final InspectionItemStateStore itemStore;
  private final InspectionSight sight;
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
    InspectionSight.Sight seen = sight.of(row, viewer);
    if (hidden(row, viewer, seen)) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    List<InspectionDetail.Item> shownItems = visibleItems(row, roomRecordId, memberId, gate, seen);
    InspectionState now = store.current(row);
    InspectionDetail.SideBySide sideBySide =
        row.getKind() == InspectionKind.MOVE_OUT
            ? sideBySide(roomRecordId, memberId, gate, shownItems)
            : null;
    return new InspectionDetail(
        row.getId(),
        roomRecordId,
        row.getKind(),
        seen.shownStatus(),
        viewer,
        row.getAuthorSide(),
        row.isLate(),
        row.getLockedAt(),
        row.getLockReason(),
        row.getCreatedAt(),
        seen.asOf(),
        shownItems,
        rules.counterpartCanRequestAt(now),
        actions(row, now, gate, seen),
        sideBySide);
  }

  /** 공유를 그만둔 뒤 상대가 시작해 이 사람이 볼 수 없는 점검인가(불변식 18). 그 점검은 있는지도 알 수 없다. */
  private static boolean hidden(Inspection row, PartySide viewer, InspectionSight.Sight seen) {
    return viewer != row.getAuthorSide()
        && seen.asOf() != null
        && row.getCreatedAt().isAfter(seen.asOf());
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
      InspectionSight.Sight seen = sight.of(mi, gate.side());
      if (!hidden(mi, gate.side(), seen)) {
        moveIn =
            new InspectionDetail.MoveIn(
                mi.getId(), seen.shownStatus(), mi.getLockedAt(), mi.isLate(), mi.getCreatedAt());
        moveInItems = visibleItems(mi, roomRecordId, memberId, gate, seen);
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

  private List<InspectionDetail.Item> visibleItems(
      Inspection row, UUID roomRecordId, UUID memberId, RoomGate gate, InspectionSight.Sight seen) {
    List<InspectionItem> shown =
        items.findByInspectionIdOrderByIdAsc(row.getId()).stream().filter(seen::sees).toList();
    if (shown.isEmpty()) {
      return List.of();
    }
    List<PhotoTarget> targets =
        shown.stream()
            .<PhotoTarget>map(
                item -> new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()))
            .toList();
    Map<PhotoTarget, List<PhotoCard>> cards =
        photos.cards(access.require(roomRecordId, memberId), targets);
    Instant at = clock.instant();
    List<InspectionDetail.Item> result = new ArrayList<>();
    for (InspectionItem item : shown) {
      InspectionItemVersion version =
          versions
              .findById(item.getCurrentVersionId())
              .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + item.getId()));
      String partName = rooms.findPart(roomRecordId, version.getRoomPartId()).name();
      InspectionItemState state = itemStore.current(row, item, gate.side(), seen);
      result.add(
          new InspectionDetail.Item(
              item.getId(),
              item.getStatus(),
              item.getAddedBySide(),
              item.isAddedAfterLock(),
              item.isAddedAfterEnd(),
              item.getCreatedAt(),
              new InspectionDetail.Version(
                  version.getNo(), version.getRoomPartId(), partName, version.getDescription()),
              cards.getOrDefault(
                  new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()), List.of()),
              itemRules.nextRerequestAt(state),
              itemActions(state, gate, at)));
    }
    return List.copyOf(result);
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
    return List.copyOf(result);
  }
}
