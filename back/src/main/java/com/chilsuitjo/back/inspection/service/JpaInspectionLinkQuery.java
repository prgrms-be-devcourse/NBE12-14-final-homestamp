package com.chilsuitjo.back.inspection.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionVisibility;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.dto.InspectionForLink;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.repository.InspectionItemRepository;
import com.chilsuitjo.back.inspection.repository.InspectionRepository;
import com.chilsuitjo.back.inspection.repository.InspectionSight;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import com.chilsuitjo.back.room.service.RoomQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InspectionLinkQuery}의 진짜 구현. 점검·항목·판과 행동 기록을 읽기만 한다.
 *
 * <p>보이는 범위는 회원 상세와 같은 부품을 쓴다: 점검 상태·항목 가리기·항목 응답과 판은 {@link InspectionSight}, 경과는 경과·내려받기와 같은
 * {@link ActionVisibility#select}. 회원 상세와 다른 것은 작성 중 점검을 보는 측과 상관없이 보이지 않는 것(03 3-1 링크 열람자 열)뿐이다.
 *
 * <p>비로그인 공개 경로라 항목 수와 상관없이 읽는 횟수가 같다(#397 리뷰): 점검·자리 사실·항목·행동 기록(점검과 모든 항목 한 번)·판·부위를 한 번씩 읽고, 행동
 * 기록은 보이는 범위·항목 응답·경과가 함께 쓴다.
 */
@Service
@RequiredArgsConstructor
public class JpaInspectionLinkQuery implements InspectionLinkQuery {

  private final InspectionRepository inspections;
  private final InspectionItemRepository items;
  private final InspectionSight sight;
  private final RoomQueryService rooms;
  private final ActionReader actions;
  private final CounterpartReader parties;

  @Override
  @Transactional(readOnly = true)
  public Optional<InspectionForLink> forLink(
      UUID roomRecordId, UUID inspectionId, PartySide viewerSide) {
    return inspections
        .findByIdAndRoomRecordId(inspectionId, roomRecordId)
        .flatMap(row -> view(row, viewerSide));
  }

  private Optional<InspectionForLink> view(Inspection row, PartySide viewer) {
    RoomParties room = parties.read(row.getRoomRecordId(), viewer);
    List<InspectionItem> all = items.findByInspectionIdOrderByIdAsc(row.getId());
    List<ActionTarget> targets = new ArrayList<>();
    targets.add(ActionTarget.inspection(row.getId()));
    all.forEach(item -> targets.add(ActionTarget.of(TargetKind.INSPECTION_ITEM, item.getId())));
    List<ActionEntry> recorded = actions.findByTargets(row.getRoomRecordId(), targets);

    InspectionSight.Sight seen = sight.of(row, viewer, room, recorded);
    if (seen.draft() || seen.hides(row)) {
      return Optional.empty();
    }
    List<InspectionItem> shown = all.stream().filter(seen::sees).toList();
    Map<UUID, InspectionSight.ItemSight> itemSights = sight.items(shown, seen, recorded);
    Map<UUID, RoomPartSummary> parts =
        rooms.findParts(
            row.getRoomRecordId(),
            itemSights.values().stream().map(it -> it.version().getRoomPartId()).toList());
    // 보이는 범위를 고정한 시각 뒤에 잠겼으면 잠기기 전 모습이라 잠긴 시각·이유도 보이지 않는다(불변식 18, 회원 상세와 같음)
    boolean shownLocked = seen.shownStatus() == InspectionStatus.LOCKED;
    return Optional.of(
        new InspectionForLink(
            row.getKind(),
            seen.shownStatus(),
            row.getAuthorSide(),
            row.isLate(),
            shownLocked ? row.getLockedAt() : null,
            shownLocked ? row.getLockReason() : null,
            row.getCreatedAt(),
            seen.asOf(),
            shown.stream().map(item -> item(item, itemSights.get(item.getId()), parts)).toList(),
            history(room, recorded, shown, viewer)));
  }

  private static InspectionForLink.Item item(
      InspectionItem item, InspectionSight.ItemSight seen, Map<UUID, RoomPartSummary> parts) {
    InspectionItemVersion version = seen.version();
    return new InspectionForLink.Item(
        item.getId(),
        seen.status(),
        item.getAddedBySide(),
        item.isAddedAfterLock(),
        item.isAddedAfterEnd(),
        item.getCreatedAt(),
        version.getNo(),
        parts.get(version.getRoomPartId()).name(),
        version.getDescription(),
        new PhotoTarget.Ref(PhotoTargetType.INSPECTION_ITEM, item.getId()));
  }

  /**
   * 이 점검과 보이는 항목의 행동 가운데 이 측에게 보이는 것. 고르는 기준은 경과·내려받기와 같다({@link ActionVisibility#select}: 공유 중단 기준
   * 시각 뒤 상대·서버 행동, 통지 상태, 작성 중 초안을 뺀다). 기준 측을 알려고 점검 시작도 함께 읽은 기록({@code recorded})을 쓴다.
   */
  private static List<InspectionForLink.History> history(
      RoomParties room, List<ActionEntry> recorded, List<InspectionItem> shown, PartySide viewer) {
    ActionVisibility.Cutoff cutoff =
        ActionVisibility.cutoff(
            room.mine().sharingStoppedAt(),
            room.counterpart().sharingStoppedAt(),
            room.counterpart().withdrawn());
    Set<UUID> shownIds = shown.stream().map(InspectionItem::getId).collect(Collectors.toSet());
    return ActionVisibility.select(recorded, viewer, cutoff).stream()
        .filter(entry -> entry.target() != null)
        .filter(entry ->
            entry.target().kind() == TargetKind.INSPECTION
                || shownIds.contains(entry.target().id()))
        .map(JpaInspectionLinkQuery::history)
        .toList();
  }

  private static InspectionForLink.History history(ActionEntry entry) {
    UUID itemId = entry.target().kind() == TargetKind.INSPECTION_ITEM ? entry.target().id() : null;
    return new InspectionForLink.History(
        entry.type(), entry.actorSide(), entry.createdAt(), itemId);
  }
}
