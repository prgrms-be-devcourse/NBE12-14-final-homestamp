package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomPartList;
import com.chilsuitjo.back.room.dto.RoomPartView;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomPart;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartStateStore;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RoomPartRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.PartState;
import com.chilsuitjo.back.room.rule.PartTransitions;
import com.chilsuitjo.back.room.rule.PartVisibility;
import com.chilsuitjo.back.room.rule.PartZone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보는 사람에게 보이는 부위(06 4-3 부위 API의 응답). 목록과 바꾼 뒤 응답(같은 키로 다시 온 요청 포함)이 같은 기준을 쓰게 모아 둔다.
 *
 * <p><b>공유를 그만둔 뒤</b>(어느 쪽이든, 보는 사람의 상대가 탈퇴하지 않았을 때)에는 보이는 부위와 상태를 그만둔 시각으로 고정한다(03 6-6 9번, 3-1,
 * 불변식 18, #180 리뷰). 그 시각까지의 부위 행동과 보는 사람 측의 행동만으로 다시 계산한다({@link PartVisibility}). 그래서 그 뒤 상대가 더한
 * 부위는 빠지고, 상대가 바꾼 숨김·켜고 끔은 드러나지 않는다. 같은 키로 다시 온 요청은 pipeline이 호실 단위 거절보다 먼저 처음 결과를 찾아 주므로 ({@code
 * SHARING_STOPPED} 전에 재응답), 그 응답도 여기서 만들어야 지금 행이 새지 않는다.
 *
 * <p>행동 기록 읽기({@code ActionReader})는 트랜잭션 안에서만 되므로 읽기 트랜잭션을 연다. pipeline 트랜잭션이 끝난 뒤 부른다.
 */
@Component
@RequiredArgsConstructor
public class PartViews {

  private static final Set<ActionType> PART_ACTIONS =
      EnumSet.of(
          ActionType.ADD_PART, ActionType.HIDE_PART, ActionType.SHOW_PART, ActionType.TOGGLE_PART);

  private final PartStateStore store;
  private final PartTransitions rules;
  private final RoomPartRepository parts;
  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final ActionReader actionReader;

  /** 부위 목록(숨긴 부위 포함). 구역 순서(V7) 다음 호실 안 순서. 당사자가 아니면 {@code NOT_FOUND}. */
  @Transactional(readOnly = true)
  public RoomPartList list(UUID roomRecordId, UUID memberId) {
    Viewer viewer = viewer(roomRecordId, memberId);
    List<RoomPart> rows =
        parts.findAllByRoomRecordId(roomRecordId).stream()
            .sorted(
                Comparator.comparingInt((RoomPart part) -> PartZone.rank(part.getZone()))
                    .thenComparingInt(RoomPart::getSortOrder)
                    .thenComparing(RoomPart::getId))
            .toList();
    if (viewer.asOf() == null) {
      return new RoomPartList(null, rows.stream().map(this::view).toList());
    }
    Map<UUID, List<ActionEntry>> byPart = partActions(roomRecordId);
    List<RoomPartView> shown = new ArrayList<>();
    for (RoomPart row : rows) {
      PartState seen = seen(row, byPart.getOrDefault(row.getId(), List.of()), viewer);
      if (seen.exists()) {
        shown.add(view(row, seen));
      }
    }
    return new RoomPartList(viewer.asOf(), shown);
  }

  /**
   * 이 회원에게 보이는 부위 하나. 바꾼 뒤 응답과 같은 키로 다시 온 요청의 응답에 쓴다. 보이지 않는 부위면 {@code NOT_FOUND}(그 회원이 남긴 행동의
   * 대상이라 보통은 없다).
   */
  @Transactional(readOnly = true)
  public RoomPartView one(UUID roomRecordId, UUID memberId, UUID partId) {
    Viewer viewer = viewer(roomRecordId, memberId);
    RoomPart row =
        parts
            .findByIdAndRoomRecordId(partId, roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (viewer.asOf() == null) {
      return view(row);
    }
    PartState seen =
        seen(row, partActions(roomRecordId).getOrDefault(row.getId(), List.of()), viewer);
    if (!seen.exists()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return view(row, seen);
  }

  private Viewer viewer(UUID roomRecordId, UUID memberId) {
    RoomRecord room =
        rooms.findById(roomRecordId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomSeats seats = new RoomSeats(room, parties.findByRoomRecordId(roomRecordId));
    Party mine = seats.seatOf(memberId);
    return new Viewer(mine, seats.visibleCutoffFor(mine));
  }

  /** 기본 부위는 행동 없이 복사되므로 "있음·켬·보임"에서, 직접 더한 부위는 "없음"에서 다시 계산한다. */
  private PartState seen(RoomPart row, List<ActionEntry> actions, Viewer viewer) {
    PartState start =
        row.getTemplateId() == null ? PartState.NONE : PartState.copied(store.toggleable(row));
    return PartVisibility.asSeenBy(rules, start, actions, viewer.mine().getSide(), viewer.asOf());
  }

  /** 이 호실의 부위 행동을 부위별로, {@code room_seq} 순. */
  private Map<UUID, List<ActionEntry>> partActions(UUID roomRecordId) {
    Map<UUID, List<ActionEntry>> byPart = new HashMap<>();
    for (ActionEntry entry : actionReader.findByRoom(roomRecordId)) {
      if (PART_ACTIONS.contains(entry.type())
          && entry.target() != null
          && entry.target().kind() == TargetKind.ROOM_PART) {
        byPart.computeIfAbsent(entry.target().id(), id -> new ArrayList<>()).add(entry);
      }
    }
    return byPart;
  }

  private RoomPartView view(RoomPart part) {
    return new RoomPartView(
        part.getId(),
        part.getZone(),
        part.getName(),
        part.isEnabled(),
        part.isHidden(),
        store.toggleable(part));
  }

  /** 다시 계산한 상태로 보인 부위. 구역·이름은 바뀌지 않는 칸이라 행에서 읽는다. */
  private RoomPartView view(RoomPart part, PartState seen) {
    return new RoomPartView(
        part.getId(),
        part.getZone(),
        part.getName(),
        seen.enabled(),
        seen.hidden(),
        store.toggleable(part));
  }

  /**
   * @param asOf 보이는 범위를 고정하는 시각. 없으면 null
   */
  private record Viewer(Party mine, Instant asOf) {}
}
