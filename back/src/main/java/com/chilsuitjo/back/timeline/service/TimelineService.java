package com.chilsuitjo.back.timeline.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionPhotos;
import com.chilsuitjo.back.action.rule.ActionVisibility;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.timeline.dto.RoomTimeline;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호실 경과(06 REP-11). 행동 기록을 내려받기(DL-1)와 같은 보이는 범위({@link ActionVisibility}, 03 3-1)로 걸러 시간순으로 준다.
 *
 * <p>통지 상태(보낸 측에만, 03 K "보낸 사람에게")는 아직 넣지 않는다. 통지 행({@code notice})을 읽는 창구가 {@code notification}에
 * 없고, 서버 행동(재통지 등)은 측이 없어 보낸 측을 행동에서 알 수 없다. 창구가 생기면 칸을 더한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

  private final RoomAccessService access;
  private final CounterpartReader parties;
  private final ActionReader actions;

  /** 그 호실 회원의 경과. 당사자가 아니면 {@code NOT_FOUND}다(ROOM-2a). 거주 종료·정산 완료·공유를 그만둔 뒤에도 본다. */
  @Transactional(readOnly = true)
  public RoomTimeline timeline(UUID roomRecordId, UUID memberId) {
    RoomAccess viewer = access.require(roomRecordId, memberId);
    RoomParties room = parties.read(roomRecordId, viewer.side());
    ActionVisibility.Cutoff cutoff =
        ActionVisibility.cutoff(
            room.mine().sharingStoppedAt(),
            room.counterpart().sharingStoppedAt(),
            room.counterpart().withdrawn());
    List<ActionEntry> all = actions.findByRoom(roomRecordId);
    // 지운 사진은 기준 시각 뒤에 지웠어도 고정 표시다(불변식 16). 그래서 걸러지기 전 전체에서 찾는다
    Map<UUID, ActionPhotos.Erased> erased = ActionPhotos.erased(all);
    List<RoomTimeline.Item> items =
        ActionVisibility.select(all, viewer.side(), cutoff).stream()
            .map(entry -> item(entry, erased))
            .toList();
    return new RoomTimeline(viewer.side(), cutoff.shared(), items);
  }

  private static RoomTimeline.Item item(ActionEntry entry, Map<UUID, ActionPhotos.Erased> erased) {
    List<RoomTimeline.Photo> photos =
        ActionPhotos.attached(entry).stream()
            .map(p -> new RoomTimeline.Photo(p.photoId(), trace(erased.get(p.photoId()))))
            .toList();
    return new RoomTimeline.Item(
        entry.id(),
        entry.createdAt(),
        entry.type(),
        entry.actorSide(),
        target(entry),
        entry.reason(),
        ActionPhotos.payloadWithoutPhotos(entry),
        photos);
  }

  private static RoomTimeline.Erased trace(ActionPhotos.Erased erased) {
    return erased == null ? null : new RoomTimeline.Erased(erased.side(), erased.at());
  }

  private static RoomTimeline.Target target(ActionEntry entry) {
    if (entry.target() == null || entry.target().kind() == TargetKind.ROOM_RECORD) {
      return null;
    }
    return new RoomTimeline.Target(entry.target().kind(), entry.target().id());
  }
}
