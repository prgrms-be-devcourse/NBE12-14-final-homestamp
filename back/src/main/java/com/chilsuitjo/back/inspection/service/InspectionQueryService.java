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
import com.chilsuitjo.back.inspection.repository.InspectionItemVersionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.PhotoService;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.room.service.RoomQueryService;
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
  private final CounterpartReader parties;
  private final RoomQueryService rooms;
  private final PhotoService photos;
  private final RoomAccessService access;

  /**
   * 점검 하나(06 4-7, S-14). 없는 ID, 그 호실 당사자가 아님, 공유를 그만둔 뒤 상대가 시작해 볼 수 없는 점검(불변식 18)은 모두 {@code
   * NOT_FOUND}다(있는지도 알 수 없게, ROOM-2a).
   *
   * <p><b>작성 중</b>이면 기준 측이 아닌 사람에게는 "작성 중"만 보인다(06 INSP-1a, 03 3-1 "작성 중 점검"): 항목을 주지 않는다({@code
   * items} null). 그 밖에는 보는 사람 자신이 더한 항목과, 공유를 그만둔 시각까지 상대가 더한 항목을 준다(불변식 18).
   *
   * <p>{@code availableActions}는 실제 요청과 같은 판단으로 만든다: 호실 단위 거절은 {@code RoomGate.rejection}, 점검 규칙은 각
   * Transitions의 판단. 지금은 작성 중 기준 측의 항목 더하기({@code items})만 있다.
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
    boolean author = viewer == row.getAuthorSide();
    Instant asOf = sharingCutoff(parties.read(roomRecordId, viewer));
    if (!author && asOf != null && row.getCreatedAt().isAfter(asOf)) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    boolean drafting = row.getStatus() == InspectionStatus.DRAFT && !author;
    List<InspectionDetail.Item> shownItems =
        drafting ? null : visibleItems(row, roomRecordId, memberId, viewer, asOf);
    return new InspectionDetail(
        row.getId(),
        roomRecordId,
        row.getKind(),
        row.getStatus(),
        viewer,
        row.getAuthorSide(),
        row.isLate(),
        row.getLockedAt(),
        row.getLockReason(),
        row.getCreatedAt(),
        asOf,
        shownItems,
        actions(row, gate));
  }

  private List<InspectionDetail.Item> visibleItems(
      Inspection row, UUID roomRecordId, UUID memberId, PartySide viewer, Instant asOf) {
    List<InspectionItem> shown =
        items.findByInspectionIdOrderByIdAsc(row.getId()).stream()
            .filter(item ->
                item.getAddedBySide() == viewer
                    || asOf == null
                    || !item.getCreatedAt().isAfter(asOf))
            .toList();
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
    List<InspectionDetail.Item> result = new ArrayList<>();
    for (InspectionItem item : shown) {
      InspectionItemVersion version =
          versions
              .findById(item.getCurrentVersionId())
              .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + item.getId()));
      String partName = rooms.findPart(roomRecordId, version.getRoomPartId()).name();
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
                  new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()), List.of())));
    }
    return List.copyOf(result);
  }

  private static List<String> actions(Inspection row, RoomGate gate) {
    List<String> result = new ArrayList<>();
    if (InspectionItemTransitions.canAdd(row.getStatus(), row.getAuthorSide(), gate.side())
        && gate.rejection(ActionType.ADD_ITEM).isEmpty()) {
      result.add("items");
    }
    return List.copyOf(result);
  }

  /**
   * 이 호실에서 보이는 범위를 고정하는 시각(03 6-6 9번, 불변식 18). 제한이 살아 있는 공유 중단 시각 중 이른 것이고, 없으면 null이다. 상대가 탈퇴해 풀린
   * 공유 중단은 보지 않는다({@code RoomParties}가 보는 사람 기준으로 판단). 수리 건의 {@code
   * RepairStateStore.sharingCutoff}와 같은 기준이다.
   */
  private static Instant sharingCutoff(RoomParties room) {
    Instant stopped = null;
    if (room.mySharingStoppedApplies()) {
      stopped = room.mine().sharingStoppedAt();
    }
    if (room.counterpartSharingStoppedApplies()) {
      Instant theirs = room.counterpart().sharingStoppedAt();
      stopped = stopped == null || theirs.isBefore(stopped) ? theirs : stopped;
    }
    return stopped;
  }
}
