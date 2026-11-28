package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
   *   <li>상대가 탈퇴했으면({@code departed}, #288): 남아 있는 공유 중단 시각(누가 그만뒀든) 중 이른 것. 상태는 다시 계산하지 않고 지금 값이다
   *       ({@code asOf} null, 수리 {@code RepairSight}와 같은 방향, decisions 2026-11-27). 그 시각 뒤 탈퇴자가 시작한
   *       점검·더한 항목·만든 판은 계속 보이지 않는다(03 6-4). 그 시각에 작성 중이던 탈퇴자의 점검은 그 뒤 확인 요청을 보냈어도 작성 중으로 본다(상대가 본
   *       적이 없음)
   * </ul>
   */
  public Sight of(Inspection row, PartySide viewer) {
    RoomParties room = parties.read(row.getRoomRecordId(), viewer);
    return sharingCutoff(room) == null && departedCutoff(room) == null
        ? of(row, viewer, room, List.of())
        : of(row, viewer, room, actions.findByTarget(ActionTarget.inspection(row.getId())));
  }

  /**
   * {@link #of(Inspection, PartySide)}와 같고, 이미 읽은 자리 사실과 행동 기록을 쓴다(한 번만 읽기, #397 리뷰).
   *
   * <p><b>실패하면 닫힌 쪽으로</b>({@link #items}와 같음, #396): 보는 사람 측의 행동을 더해 다시 계산할 수 없으면(순서가 맞지 않아 전이 규칙이
   * 거절) 그 시각까지의 행동만으로 본다. 그 시각까지의 행동마저 다시 계산할 수 없거나, 볼 수 있는 점검인데 보이는 행동에 점검이 없으면 지금 행 값으로 돌아가지 않고
   * {@link IllegalStateException}을 던진다. 공유를 그만둔 뒤 상대가 시작한 점검({@link Sight#hides})은 보이는 행동이 없는 것이
   * 맞으므로, 작성 중으로 보고 상대 항목을 모두 가린 값을 준다(부르는 쪽은 {@code hides}로 먼저 막는다).
   *
   * @param room 보는 측 기준으로 읽은 자리 사실
   * @param recorded 이 점검을 가리키는 행동을 모두 담은 행동 기록({@code room_seq} 순). 다른 대상의 행동이 섞여 있어도 된다
   */
  public Sight of(Inspection row, PartySide viewer, RoomParties room, List<ActionEntry> recorded) {
    Instant asOf = sharingCutoff(room);
    if (asOf == null) {
      Instant departed = departedCutoff(room);
      boolean draft =
          row.getStatus() == InspectionStatus.DRAFT
              || (departed != null
                  && viewer != row.getAuthorSide()
                  && draftAt(row, history(row, recorded), departed));
      return new Sight(
          viewer, null, departed, row.getStatus(), viewer != row.getAuthorSide() && draft, draft);
    }
    if (startedAfter(row, viewer, asOf)) {
      return new Sight(viewer, asOf, InspectionStatus.DRAFT, true, true);
    }
    List<ActionEntry> history = history(row, recorded);
    Predicate<ActionEntry> beforeCutoff = e -> !e.createdAt().isAfter(asOf);
    InspectionState atCutoff;
    try {
      atCutoff = Replay.fold(rules, history.stream().filter(beforeCutoff).toList());
    } catch (BusinessException broken) {
      throw new IllegalStateException("고정한 시각까지의 점검 기록을 다시 계산할 수 없어요: " + row.getId(), broken);
    }
    InspectionState shown;
    try {
      shown =
          Replay.fold(
              rules,
              history.stream().filter(beforeCutoff.or(e -> e.actorSide() == viewer)).toList());
    } catch (BusinessException rejected) {
      shown = atCutoff;
    }
    if (!shown.exists()) {
      throw new IllegalStateException("이 사람에게 보이는 점검 기록이 없어요: " + row.getId());
    }
    // 고정한 시각에 아직 없던 점검은 보는 사람이 그 뒤 시작한 것이라 보이는 상태로만 본다
    boolean draft =
        shown.status() == InspectionStatus.DRAFT
            || (atCutoff.exists() && atCutoff.status() == InspectionStatus.DRAFT);
    return new Sight(viewer, asOf, shown.status(), viewer != row.getAuthorSide() && draft, draft);
  }

  /** 이 점검의 상태를 바꾸는 행동만({@code room_seq} 순). */
  private static List<ActionEntry> history(Inspection row, List<ActionEntry> recorded) {
    ActionTarget target = ActionTarget.inspection(row.getId());
    return recorded.stream()
        .filter(entry -> target.equals(entry.target()))
        .filter(entry -> InspectionTransitions.CHANGES.contains(entry.type()))
        .toList();
  }

  /**
   * 그 시각에 작성 중이었나(아직 없었어도 작성 중으로 본다). 기록된 행동의 앞부분이라 다시 계산이 실패하면 기록이 어긋난 것이라 {@link
   * IllegalStateException}이다.
   */
  private boolean draftAt(Inspection row, List<ActionEntry> history, Instant at) {
    InspectionState then;
    try {
      then = Replay.fold(rules, history.stream().filter(e -> !e.createdAt().isAfter(at)).toList());
    } catch (BusinessException broken) {
      throw new IllegalStateException("고정한 시각까지의 점검 기록을 다시 계산할 수 없어요: " + row.getId(), broken);
    }
    return !then.exists() || then.status() == InspectionStatus.DRAFT;
  }

  /** 공유를 그만둔 뒤 상대가 시작한 점검인가(불변식 18). {@link Sight#hides}와 같은 판단이다. */
  private static boolean startedAfter(Inspection row, PartySide viewer, Instant asOf) {
    return viewer != row.getAuthorSide() && asOf != null && row.getCreatedAt().isAfter(asOf);
  }

  /**
   * 이 사람에게 보이는 항목들의 응답과 판(불변식 18). 판은 한 번에 읽고, 행동 기록은 부르는 쪽이 읽은 것을 쓴다(항목마다 읽지 않기, #397 리뷰).
   *
   * <ul>
   *   <li>응답: 보이는 범위를 고정하지 않았으면 지금 값이다. 고정했으면 그 시각까지의 행동과 보는 사람 측의 행동만으로 다시 계산해({@link #of}의 점검 상태와
   *       같은 방법), 고정 뒤 상대가 답하거나 보충한 것이 드러나지 않는다
   *   <li>판: 고정하지 않았거나 자기가 더한 항목이면 지금 판이다. 상대가 더한 항목은 고정한 시각까지 만든 판 가운데 마지막 것이다
   *   <li>마지막 보충 시각: 응답과 같은 행동으로 본 값이다. 고정하지 않았으면 기록된 보충 가운데 마지막 것이다
   * </ul>
   *
   * <p><b>실패하면 닫힌 쪽으로</b>(#397 리뷰): 보는 사람 측의 행동을 더해 다시 계산할 수 없으면(순서가 맞지 않아 전이 규칙이 거절) 그 시각까지의 행동만으로
   * 계산한다. 기록된 행동의 앞부분이라 기록될 때 이미 규칙을 통과했으므로, 덜 보일 뿐 고정 뒤 상대의 변경은 보이지 않는다. 그래도 항목이 없거나 계산할 수 없으면 지금
   * 값으로 돌아가지 않고 {@link IllegalStateException}을 던진다(보여서는 안 되는 항목을 넘겼거나 기록이 어긋남).
   *
   * <p>지금 항목 응답을 바꾸는 행동은 더하기·응답·보충뿐이고, 판은 더할 때 만든 #1뿐이라 다시 계산의 판 번호가 응답의 본 판과 맞는다. 미확인 표시({@code
   * MARK_UNCONFIRMED}, #370 A-9)나 판을 바꾸는 항목 고치기가 들어오면 여기도 같이 맞춘다. 상대가 탈퇴하면 응답은 지금 값이고, 탈퇴자가 남은 중단
   * 시각 뒤 만든 판·보충 시각만 뺀다({@link #of}와 같음, #288).
   *
   * @param shown 이 사람에게 보이는 항목({@link Sight#sees}로 거른 것)
   * @param seen 이 사람의 {@link #of} 결과
   * @param recorded 이 항목들을 가리키는 행동을 모두 담은 행동 기록({@code room_seq} 순). 다른 대상의 행동이 섞여 있어도 된다. 범위를 고정하지
   *     않았으면 보지 않는다
   * @return 항목 ID → 보이는 응답과 판. {@code shown}의 순서를 따른다
   */
  public Map<UUID, ItemSight> items(
      List<InspectionItem> shown, Sight seen, List<ActionEntry> recorded) {
    if (shown.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<InspectionItemVersion>> byItem =
        versions
            .findByInspectionItemIdInOrderByInspectionItemIdAscNoAsc(
                shown.stream().map(InspectionItem::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(InspectionItemVersion::getInspectionItemId));
    Map<UUID, List<ActionEntry>> changes =
        recorded.stream()
            .filter(entry -> entry.target() != null)
            .filter(entry -> entry.target().kind() == TargetKind.INSPECTION_ITEM)
            .filter(entry -> ITEM_CHANGES.contains(entry.type()))
            .collect(Collectors.groupingBy(entry -> entry.target().id()));
    Map<UUID, ItemSight> out = new LinkedHashMap<>();
    for (InspectionItem item : shown) {
      List<InspectionItemVersion> all = byItem.getOrDefault(item.getId(), List.of());
      List<ActionEntry> own = changes.getOrDefault(item.getId(), List.of());
      if (seen.asOf() == null) {
        // 상대가 탈퇴했으면 응답은 지금 값이고(#314 한계), 탈퇴자가 그 시각 뒤 만든 판·보충 시각만 뺀다(#288)
        List<ActionEntry> kept =
            own.stream()
                .filter(entry -> seen.seesAction(entry.actorSide(), entry.createdAt()))
                .toList();
        out.put(
            item.getId(),
            new ItemSight(item.getStatus(), itemVersion(item, seen, all), lastSupplementAt(kept)));
        continue;
      }
      InspectionItemState state = itemState(item, seen, own);
      out.put(
          item.getId(),
          new ItemSight(state.status(), itemVersion(item, seen, all), state.lastRerequestAt()));
    }
    return out;
  }

  private static Instant lastSupplementAt(List<ActionEntry> changes) {
    return changes.stream()
        .filter(entry -> entry.type() == ActionType.SUPPLEMENT_ITEM)
        .map(ActionEntry::createdAt)
        .max(Instant::compareTo)
        .orElse(null);
  }

  private InspectionItemState itemState(
      InspectionItem item, Sight seen, List<ActionEntry> changes) {
    Predicate<ActionEntry> beforeCutoff = e -> !e.createdAt().isAfter(seen.asOf());
    InspectionItemState state;
    try {
      state = foldItem(changes, beforeCutoff.or(e -> e.actorSide() == seen.viewer()));
    } catch (BusinessException rejected) {
      try {
        state = foldItem(changes, beforeCutoff);
      } catch (BusinessException broken) {
        throw new IllegalStateException("고정한 시각까지의 항목 기록을 다시 계산할 수 없어요: " + item.getId(), broken);
      }
    }
    if (!state.exists()) {
      throw new IllegalStateException("이 사람에게 보이지 않는 항목이에요: " + item.getId());
    }
    return state;
  }

  private InspectionItemState foldItem(List<ActionEntry> changes, Predicate<ActionEntry> visible) {
    return Replay.fold(itemRules, changes.stream().filter(visible).toList());
  }

  private static InspectionItemVersion itemVersion(
      InspectionItem item, Sight seen, List<InspectionItemVersion> all) {
    if (seen.asOf() == null
        && seen.departed() != null
        && !Objects.equals(item.getAddedBySide(), seen.viewer())) {
      return all.stream()
          .filter(version -> !version.getCreatedAt().isAfter(seen.departed()))
          .reduce((earlier, later) -> later)
          .orElseThrow(() -> new IllegalStateException("탈퇴 기준 시각까지의 판이 없어요: " + item.getId()));
    }
    if (seen.asOf() == null || Objects.equals(item.getAddedBySide(), seen.viewer())) {
      return all.stream()
          .filter(version -> version.getId().equals(item.getCurrentVersionId()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("지금 판이 없어요: " + item.getId()));
    }
    return all.stream()
        .filter(version -> !version.getCreatedAt().isAfter(seen.asOf()))
        .reduce((earlier, later) -> later)
        .orElseThrow(() -> new IllegalStateException("고정한 시각까지의 판이 없어요: " + item.getId()));
  }

  /**
   * 한 사람에게 보이는 항목 응답과 판({@link #items}).
   *
   * @param status 이 사람에게 보이는 항목 응답
   * @param version 이 사람에게 보이는 판
   * @param lastSupplementAt 이 사람에게 보이는 마지막 보충 시각(응답과 같은 행동으로 본다). 없으면 null. 상세의 {@code
   *     nextRerequestAt}이 쓴다(#396: 고정 뒤 상대의 보충 시각이 드러나지 않게)
   */
  public record ItemSight(
      AckState status, InspectionItemVersion version, Instant lastSupplementAt) {}

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

  /**
   * 상대가 탈퇴했을 때 그 측 기록을 계속 빼는 시각(#288): 남아 있는 공유 중단 시각(누가 그만뒀든) 중 이른 것. 상대가 탈퇴하지 않았거나 아무도 그만둔 적이 없으면
   * null이다. 수리 {@code RepairSight.departed}·호실 {@code RoomSeats.departedCutoffFor}와 같은 기준이고, 탈퇴 처리가
   * {@code sharing_stopped_at}을 지우지 않는다는 전제다(decisions 2026-11-26).
   */
  static Instant departedCutoff(RoomParties room) {
    if (!room.counterpart().withdrawn()) {
      return null;
    }
    Instant mine = room.mine().sharingStoppedAt();
    Instant theirs = room.counterpart().sharingStoppedAt();
    if (mine == null) {
      return theirs;
    }
    return theirs == null || mine.isBefore(theirs) ? mine : theirs;
  }

  /**
   * 한 사람에게 보이는 점검.
   *
   * @param viewer 보는 사람의 측
   * @param asOf 보이는 범위를 고정하는 시각. 없으면 null
   * @param departed 상대가 탈퇴했을 때 그 측 기록을 계속 빼는 시각({@link #departedCutoff}). {@code asOf}와 함께 있지 않다.
   *     없으면 null
   * @param shownStatus 이 사람에게 보이는 점검 상태
   * @param draftHidden 기준 측이 쓴 항목을 가리나(작성 중 점검을 기준 측이 아닌 사람이 볼 때). 자기가 더한 항목은 보인다
   * @param draft 보는 측과 상관없이 작성 중으로 보나: 보이는 상태가 작성 중이거나, 고정한 시각까지의 행동만으로 작성 중이다(#352와 같은 경계)
   */
  public record Sight(
      PartySide viewer,
      Instant asOf,
      Instant departed,
      InspectionStatus shownStatus,
      boolean draftHidden,
      boolean draft) {

    /** 상대가 탈퇴하지 않은 경우({@code departed} 없음). */
    public Sight(
        PartySide viewer,
        Instant asOf,
        InspectionStatus shownStatus,
        boolean draftHidden,
        boolean draft) {
      this(viewer, asOf, null, shownStatus, draftHidden, draft);
    }

    /**
     * 공유를 그만둔 뒤 상대가 시작해 이 사람이 볼 수 없는 점검인가(불변식 18). 상대가 탈퇴했으면 남은 중단 시각 뒤 탈퇴자가 시작한 점검이다(#288). 그 점검은
     * 있는지도 알 수 없다.
     */
    public boolean hides(Inspection row) {
      return startedAfter(row, viewer, asOf) || startedAfter(row, viewer, departed);
    }

    /**
     * 이 항목이 보이나: 자신이 더한 것은 늘 보이고, 상대가 더한 것은 가리지 않을 때 범위를 고정한 시각(상대가 탈퇴했으면 남은 중단 시각)까지 더한 것만. 기준 측
     * 작성 중 항목을 가리면 상대 항목은 모두 가린다.
     */
    public boolean sees(InspectionItem item) {
      if (Objects.equals(item.getAddedBySide(), viewer)) {
        return true;
      }
      return !draftHidden
          && (asOf == null || !item.getCreatedAt().isAfter(asOf))
          && (departed == null || !item.getCreatedAt().isAfter(departed));
    }

    /** 이 측이 이 시각에 남긴 행동이 탈퇴 기준({@code departed})으로 보이나. 내 것·서버 것은 늘 보인다. */
    boolean seesAction(PartySide side, Instant at) {
      return side == null || side == viewer || departed == null || !at.isAfter(departed);
    }
  }
}
