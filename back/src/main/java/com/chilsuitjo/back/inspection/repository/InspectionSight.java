package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 한 사람에게 점검이 어디까지 보이는지(03 6-6 9번, 5장 불변식 18)와 점검 확인 요청을 열 수 있는지. 상세 조회와 항목 응답·모두 확인이 같은 판단을 쓰도록 한곳에
 * 둔다(06 4-0 "서버의 같은 함수"). 트랜잭션 안에서 부른다(행동 기록 읽기).
 */
@Component
@RequiredArgsConstructor
public class InspectionSight {

  private final CounterpartReader parties;
  private final ActionReader actions;
  private final InspectionTransitions rules;
  private final WaitService waits;

  /**
   * 이 사람에게 보이는 점검.
   *
   * <ul>
   *   <li>보이는 범위를 고정하는 시각({@code asOf}): 제한이 살아 있는 공유 중단 시각 중 이른 것. 없으면 null
   *   <li>보이는 상태: 고정됐으면 그 시각까지의 행동과 보는 사람 측의 행동만으로 다시 계산한다(수리 건의 {@code
   *       RepairQueryService.visibleState}와 같은 방법)
   *   <li>항목을 가리나: 기준 측이 아닌 사람에게 작성 중이면 가린다(06 INSP-1a). 고정됐으면 <b>그 시각까지의 행동만</b>으로 보아, 그때 작성 중이었으면
   *       그 뒤 누가 확인 요청을 보냈든 가린다(#218 리뷰)
   * </ul>
   */
  public Sight of(Inspection row, PartySide viewer) {
    Instant asOf = sharingCutoff(parties.read(row.getRoomRecordId(), viewer));
    if (asOf == null) {
      boolean hidden = viewer != row.getAuthorSide() && row.getStatus() == InspectionStatus.DRAFT;
      return new Sight(viewer, null, row.getStatus(), hidden);
    }
    List<ActionEntry> history =
        actions.findByTarget(ActionTarget.inspection(row.getId())).stream()
            .filter(entry -> InspectionTransitions.CHANGES.contains(entry.type()))
            .toList();
    InspectionStatus shown =
        folded(row, history, e -> !e.createdAt().isAfter(asOf) || e.actorSide() == viewer);
    boolean hidden =
        viewer != row.getAuthorSide()
            && (shown == InspectionStatus.DRAFT
                || folded(row, history, e -> !e.createdAt().isAfter(asOf))
                    == InspectionStatus.DRAFT);
    return new Sight(viewer, asOf, shown, hidden);
  }

  /** 지금 호실 단계에서 점검 확인 요청을 열 수 있나(03 K). */
  public boolean canOpenConfirm(UUID roomRecordId, PartySide side) {
    return parties.read(roomRecordId, side).canOpen(RequestKind.INSPECTION_CONFIRM)
        && waits.canOpen(roomRecordId, RequestKind.INSPECTION_CONFIRM);
  }

  /**
   * 점검 확인 요청을 받을 상대가 있나(#163 6-1). 상대가 연결됐거나 연결 전({@code INVITED})이고, 어느 쪽이든 공유 중단 제한이 없을 때다(03 B,
   * 불변식 18). 상대 없음·거절·탈퇴면 받을 사람이 없다.
   *
   * @param room 요청한 측 기준으로 읽은 자리 사실
   */
  static boolean reachable(RoomParties room) {
    PartyPresence presence = room.counterpart().presence();
    return !sharingStopped(room)
        && (presence == PartyPresence.CONNECTED || presence == PartyPresence.INVITED);
  }

  /** 공유 중단 제한이 살아 있나(불변식 18). 어느 쪽이 그만뒀든 같고, 상대가 탈퇴해 풀린 공유 중단은 보지 않는다({@code RoomParties}의 판단). */
  static boolean sharingStopped(RoomParties room) {
    return room.mySharingStoppedApplies() || room.counterpartSharingStoppedApplies();
  }

  /**
   * 이 호실에서 보이는 범위를 고정하는 시각(03 6-6 9번, 불변식 18). 제한이 살아 있는 공유 중단 시각 중 이른 것이고, 없으면 null이다. 수리 건의
   * {@code RepairStateStore.sharingCutoff}와 같은 기준이다.
   */
  static Instant sharingCutoff(RoomParties room) {
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

  private InspectionStatus folded(
      Inspection row, List<ActionEntry> history, Predicate<ActionEntry> visible) {
    InspectionState state = Replay.fold(rules, history.stream().filter(visible).toList());
    return state.exists() ? state.status() : row.getStatus();
  }

  /**
   * 한 사람에게 보이는 점검.
   *
   * @param viewer 보는 사람의 측
   * @param asOf 보이는 범위를 고정하는 시각. 없으면 null
   * @param shownStatus 이 사람에게 보이는 점검 상태
   * @param itemsHidden 항목을 모두 가리나(작성 중 점검을 기준 측이 아닌 사람이 볼 때)
   */
  public record Sight(
      PartySide viewer, Instant asOf, InspectionStatus shownStatus, boolean itemsHidden) {

    /** 이 항목이 보이나: 항목을 가리지 않고, 자신이 더했거나 범위를 고정한 시각까지 상대가 더한 것. */
    public boolean sees(InspectionItem item) {
      return !itemsHidden
          && (Objects.equals(item.getAddedBySide(), viewer)
              || asOf == null
              || !item.getCreatedAt().isAfter(asOf));
    }
  }
}
