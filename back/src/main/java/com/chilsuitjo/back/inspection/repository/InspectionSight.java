package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.entity.InspectionItem;
import com.chilsuitjo.back.inspection.entity.InspectionItemVersion;
import com.chilsuitjo.back.inspection.rule.InspectionItemState;
import com.chilsuitjo.back.inspection.rule.InspectionItemTransitions;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.WaitService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

  /** 항목 응답을 바꾸는 행동({@link InspectionItemTransitions#apply}가 받는 것). */
  private static final Set<ActionType> ITEM_CHANGES =
      EnumSet.of(ActionType.ADD_ITEM, ActionType.RESPOND_ITEM, ActionType.SUPPLEMENT_ITEM);

  private final CounterpartReader parties;
  private final ActionReader actions;
  private final InspectionTransitions rules;
  private final InspectionItemTransitions itemRules;
  private final InspectionItemVersionRepository versions;
  private final WaitService waits;

  /**
   * 이 사람에게 보이는 점검.
   *
   * <ul>
   *   <li>보이는 범위를 고정하는 시각({@code asOf}): 제한이 살아 있는 공유 중단 시각 중 이른 것. 없으면 null
   *   <li>보이는 상태: 고정됐으면 그 시각까지의 행동과 보는 사람 측의 행동만으로 다시 계산한다(수리 건의 {@code
   *       RepairQueryService.visibleState}와 같은 방법)
   *   <li>기준 측 항목을 가리나: 기준 측이 아닌 사람에게 작성 중이면 기준 측이 쓴 항목을 가린다(06 INSP-1a). 자기가 더한 항목은 보인다(#286).
   *       고정됐으면 <b>그 시각까지의 행동만</b>으로 보아, 그때 작성 중이었으면 그 뒤 누가 확인 요청을 보냈든 가린다(#218 리뷰)
   *   <li>작성 중으로 보나({@code draft}): 위 판단에서 보는 측을 빼고 본 값이다. 링크 열람은 보는 측과 상관없이 작성 중 점검을 보이지 않으므로(03
   *       3-1 링크 열람자 "작성 중 점검 ✕") 이 값을 쓴다
   * </ul>
   */
  public Sight of(Inspection row, PartySide viewer) {
    Instant asOf = sharingCutoff(parties.read(row.getRoomRecordId(), viewer));
    if (asOf == null) {
      boolean draft = row.getStatus() == InspectionStatus.DRAFT;
      return new Sight(
          viewer, null, row.getStatus(), viewer != row.getAuthorSide() && draft, draft);
    }
    List<ActionEntry> history =
        actions.findByTarget(ActionTarget.inspection(row.getId())).stream()
            .filter(entry -> InspectionTransitions.CHANGES.contains(entry.type()))
            .toList();
    InspectionStatus shown =
        folded(row, history, e -> !e.createdAt().isAfter(asOf) || e.actorSide() == viewer);
    boolean draft =
        shown == InspectionStatus.DRAFT
            || folded(row, history, e -> !e.createdAt().isAfter(asOf)) == InspectionStatus.DRAFT;
    return new Sight(viewer, asOf, shown, viewer != row.getAuthorSide() && draft, draft);
  }

  /**
   * 이 사람에게 보이는 항목 응답(불변식 18). 보이는 범위를 고정하지 않았으면 지금 값이다. 고정했으면 그 시각까지의 행동과 보는 사람 측의 행동만으로 다시
   * 계산해({@link #of}의 점검 상태와 같은 방법), 고정 뒤 상대가 답하거나 보충한 것이 드러나지 않는다.
   *
   * <p>지금 항목 응답을 바꾸는 행동은 더하기·응답·보충뿐이고, 판은 더할 때 만든 #1뿐이라 다시 계산의 판 번호가 응답의 본 판과 맞는다. 미확인 표시({@code
   * MARK_UNCONFIRMED}, #370 A-9)나 판을 바꾸는 항목 고치기가 들어오면 여기도 같이 맞춘다. 상대가 탈퇴하면 기준 시각이 없어지는 것은 {@link
   * #of}와 같다(#288).
   *
   * @param seen 이 사람의 {@link #of} 결과. 그 항목이 이 사람에게 보여야 한다({@link Sight#sees})
   */
  public AckState itemStatus(InspectionItem item, Sight seen) {
    if (seen.asOf() == null) {
      return item.getStatus();
    }
    List<ActionEntry> history =
        actions.findByTarget(ActionTarget.of(TargetKind.INSPECTION_ITEM, item.getId())).stream()
            .filter(entry -> ITEM_CHANGES.contains(entry.type()))
            .filter(e -> !e.createdAt().isAfter(seen.asOf()) || e.actorSide() == seen.viewer())
            .toList();
    InspectionItemState state = Replay.fold(itemRules, history);
    return state.exists() ? state.status() : item.getStatus();
  }

  /**
   * 이 사람에게 보이는 항목 판(불변식 18). 보이는 범위를 고정하지 않았거나 자기가 더한 항목이면 지금 판이다. 상대가 더한 항목은 고정한 시각까지 만든 판 가운데 마지막
   * 것이라, 고정 뒤 상대가 판을 바꿔도 드러나지 않는다.
   *
   * @param seen 이 사람의 {@link #of} 결과. 그 항목이 이 사람에게 보여야 한다({@link Sight#sees})
   */
  public InspectionItemVersion itemVersion(InspectionItem item, Sight seen) {
    if (seen.asOf() == null || Objects.equals(item.getAddedBySide(), seen.viewer())) {
      return versions
          .findById(item.getCurrentVersionId())
          .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + item.getId()));
    }
    return versions.findByInspectionItemIdOrderByNoAsc(item.getId()).stream()
        .filter(version -> !version.getCreatedAt().isAfter(seen.asOf()))
        .reduce((earlier, later) -> later)
        .orElseThrow(() -> new IllegalStateException("고정한 시각까지의 판이 없어요: " + item.getId()));
  }

  /** 지금 호실 단계에서 점검 확인 요청을 열 수 있나(03 K). */
  public boolean canOpenConfirm(UUID roomRecordId, PartySide side) {
    return canOpenConfirm(parties.read(roomRecordId, side), roomRecordId);
  }

  /** {@link #canOpenConfirm(UUID, PartySide)}와 같고, 이미 읽은 자리 사실을 쓴다(한 번만 읽기). */
  public boolean canOpenConfirm(RoomParties room, UUID roomRecordId) {
    return room.canOpen(RequestKind.INSPECTION_CONFIRM)
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
   * @param draftHidden 기준 측이 쓴 항목을 가리나(작성 중 점검을 기준 측이 아닌 사람이 볼 때). 자기가 더한 항목은 보인다
   * @param draft 보는 측과 상관없이 작성 중으로 보나: 보이는 상태가 작성 중이거나, 고정한 시각까지의 행동만으로 작성 중이다(#352와 같은 경계)
   */
  public record Sight(
      PartySide viewer,
      Instant asOf,
      InspectionStatus shownStatus,
      boolean draftHidden,
      boolean draft) {

    /** 공유를 그만둔 뒤 상대가 시작해 이 사람이 볼 수 없는 점검인가(불변식 18). 그 점검은 있는지도 알 수 없다. */
    public boolean hides(Inspection row) {
      return viewer != row.getAuthorSide() && asOf != null && row.getCreatedAt().isAfter(asOf);
    }

    /**
     * 이 항목이 보이나: 자신이 더한 것은 늘 보이고, 상대가 더한 것은 가리지 않을 때 범위를 고정한 시각까지 더한 것만. 기준 측 작성 중 항목을 가리면 상대 항목은
     * 모두 가린다.
     */
    public boolean sees(InspectionItem item) {
      if (Objects.equals(item.getAddedBySide(), viewer)) {
        return true;
      }
      return !draftHidden && (asOf == null || !item.getCreatedAt().isAfter(asOf));
    }
  }
}
