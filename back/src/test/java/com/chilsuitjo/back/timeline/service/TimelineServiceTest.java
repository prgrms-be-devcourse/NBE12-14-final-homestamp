package com.chilsuitjo.back.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.chilsuitjo.back.timeline.dto.RoomTimeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 경과 중 API로 만들기 어려운 갈래(지운 사진 흔적, 호실 대상 행동). */
class TimelineServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-27T01:00:00Z");

  private final UUID room = UUID.randomUUID();
  private final UUID member = UUID.randomUUID();
  private final RoomAccessService access = mock(RoomAccessService.class);
  private final CounterpartReader parties = mock(CounterpartReader.class);
  private final ActionReader actions = mock(ActionReader.class);
  private final TimelineService service = new TimelineService(access, parties, actions);
  private final List<ActionEntry> log = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(access.require(room, member))
        .thenReturn(
            new RoomAccess(
                room,
                member,
                UUID.randomUUID(),
                PartySide.TENANT,
                PartyStatus.CONNECTED,
                RoomStatus.LIVING,
                null,
                PartyStatus.CONNECTED,
                null));
    PartyFacts connected =
        new PartyFacts(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PartyPresence.CONNECTED,
            null,
            false,
            false,
            null);
    when(parties.read(room, PartySide.TENANT))
        .thenReturn(new RoomParties(RoomPhase.values()[0], connected, connected));
    when(actions.findByRoom(room)).thenReturn(log);
  }

  @Test
  void 지운_사진은_지운_측과_시각을_흔적으로_남긴다() {
    UUID kept = UUID.randomUUID();
    UUID erased = UUID.randomUUID();
    UUID repair = UUID.randomUUID();
    log.add(
        entry(
            1,
            ActionType.REGISTER,
            ActionTarget.repairCase(repair),
            Map.of("photos", List.of(photo(kept, 0), photo(erased, 1)), "answer", "ACK")));
    log.add(entry(2, ActionType.ERASE_PHOTO, ActionTarget.photo(erased), Map.of()));

    RoomTimeline result = service.timeline(room, member);

    RoomTimeline.Item registered = result.items().getFirst();
    assertThat(registered.target().kind()).isEqualTo(TargetKind.REPAIR_CASE);
    assertThat(registered.target().id()).isEqualTo(repair);
    assertThat(registered.payload()).containsOnlyKeys("answer");
    assertThat(registered.photos())
        .extracting(RoomTimeline.Photo::photoId)
        .containsExactly(kept, erased);
    assertThat(registered.photos().get(0).erased()).isNull();
    assertThat(registered.photos().get(1).erased().side()).isEqualTo(PartySide.TENANT);
    assertThat(registered.photos().get(1).erased().at()).isEqualTo(T0.plusSeconds(2));
  }

  @Test
  void 호실_기록이_대상이거나_대상이_없는_행동은_대상이_없다() {
    log.add(entry(1, ActionType.MOVE_OUT, ActionTarget.roomRecord(room), Map.of()));
    log.add(entry(2, ActionType.MOVE_OUT, null, Map.of()));

    RoomTimeline result = service.timeline(room, member);

    assertThat(result.mySide()).isEqualTo(PartySide.TENANT);
    assertThat(result.asOf()).isNull();
    assertThat(result.items()).hasSize(2).allSatisfy(i -> assertThat(i.target()).isNull());
  }

  private static Map<String, Object> photo(UUID id, int order) {
    return Map.of("photoId", id.toString(), "originalSha256", "a".repeat(64), "sortOrder", order);
  }

  private ActionEntry entry(
      long seq, ActionType type, ActionTarget target, Map<String, Object> payload) {
    return new ActionEntry(
        UUID.randomUUID(),
        room,
        seq,
        type,
        PartySide.TENANT,
        member.toString(),
        "k" + seq,
        UUID.randomUUID(),
        target,
        null,
        null,
        null,
        payload,
        T0.plusSeconds(seq));
  }
}
