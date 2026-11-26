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
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
   * <p>보이는 상태와 항목은 {@link InspectionSight}가 정한다: <b>작성 중</b>이면 기준 측이 아닌 사람에게 "작성 중"만 보이고({@code
   * items} null, 06 INSP-1a), 그 밖에는 보는 사람 자신이 더한 항목과 공유를 그만둔 시각까지 상대가 더한 항목을 준다(불변식 18). 항목 응답·모두
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
    if (viewer != row.getAuthorSide()
        && seen.asOf() != null
        && row.getCreatedAt().isAfter(seen.asOf())) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    List<InspectionDetail.Item> shownItems =
        seen.itemsHidden() ? null : visibleItems(row, roomRecordId, memberId, gate, seen);
    InspectionState now = store.current(row);
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
        actions(row, now, gate));
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
      InspectionItemState state = itemStore.current(row, item, gate.side());
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
  private List<String> actions(Inspection row, InspectionState now, RoomGate gate) {
    List<String> result = new ArrayList<>();
    if (InspectionItemTransitions.canAdd(row.getStatus(), row.getAuthorSide(), gate.side())
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
            itemStore.confirmAll(row, gate.side()), gate.side())) {
      result.add("confirm-all");
    }
    return List.copyOf(result);
  }
}
