package com.chilsuitjo.back.download.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.correction.service.CorrectionReader;
import com.chilsuitjo.back.download.dto.RoomDownload;
import com.chilsuitjo.back.download.dto.RoomDownload.RecordPhoto;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 행동 기록만으로 만드는 내려받기 목록 중 API로 만들 수 없는 갈래(지운 사진, 호실 대상 행동, #246). */
class DownloadServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-26T01:00:00Z");
  private static final String SHA = "a".repeat(64);

  private final UUID room = UUID.randomUUID();
  private final UUID member = UUID.randomUUID();
  private final RoomAccessService access = mock(RoomAccessService.class);
  private final CounterpartReader parties = mock(CounterpartReader.class);
  private final ActionReader actions = mock(ActionReader.class);
  private final DownloadService service =
      new DownloadService(
          access,
          parties,
          actions,
          new PendingCorrections(mock(CorrectionReader.class)),
          Clock.fixed(T0, ZoneOffset.UTC));
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
  void 지운_사진은_파일_없이_지운_측과_시각과_지문값만_남고_묶음에서_빠진다() {
    UUID kept = UUID.randomUUID();
    UUID erased = UUID.randomUUID();
    UUID byOps = UUID.randomUUID();
    log.add(withPhotos(1, T0, List.of(kept, erased, byOps)));
    log.add(
        entry(
            2,
            ActionType.ERASE_PHOTO,
            PartySide.TENANT,
            ActionTarget.photo(erased),
            T0.plusSeconds(1)));
    log.add(entry(3, ActionType.ERASE_PHOTO, null, ActionTarget.photo(byOps), T0.plusSeconds(2)));

    RoomDownload result = service.download(room, member);

    assertThat(result.photoBatches()).containsExactly(List.of(kept));
    assertThat(result.photoCount()).isEqualTo(1);
    List<RecordPhoto> photos = result.record().photos();
    assertThat(photos).extracting(RecordPhoto::photoId).containsExactly(kept, erased, byOps);
    assertThat(photos).extracting(RecordPhoto::sha256).containsOnly(SHA);
    assertThat(photos.get(0).file()).isEqualTo("사진/" + kept + ".jpg");
    assertThat(photos.get(0).erased()).isNull();
    assertThat(photos.get(1).file()).isNull();
    assertThat(photos.get(1).erased().side()).isEqualTo(PartySide.TENANT);
    assertThat(photos.get(1).erased().at()).isEqualTo(T0.plusSeconds(1));
    // 다른 사람의 요청으로 운영이 지웠으면 측을 보이지 않는다(불변식 16)
    assertThat(photos.get(2).erased().side()).isNull();
    assertThat(result.record().actions().getFirst().photos().get(1).file()).isNull();
  }

  @Test
  void 공유_중단_기준_시각_뒤에_지운_사진도_지운_사진으로_남는다() {
    PartyFacts mine =
        new PartyFacts(
            UUID.randomUUID(), member, PartyPresence.CONNECTED, null, false, false, null);
    PartyFacts stopped =
        new PartyFacts(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PartyPresence.CONNECTED,
            T0.plusSeconds(10),
            false,
            false,
            null);
    when(parties.read(room, PartySide.TENANT))
        .thenReturn(new RoomParties(RoomPhase.values()[0], mine, stopped));
    UUID photo = UUID.randomUUID();
    log.add(withPhotos(1, T0, List.of(photo)));
    log.add(entry(2, ActionType.ERASE_PHOTO, null, ActionTarget.photo(photo), T0.plusSeconds(20)));

    RoomDownload result = service.download(room, member);

    // 지운 행동 자체는 기준 시각 뒤 서버 행동이라 넣지 않지만, 사진은 양쪽에 고정 표시다(03 3-1 "지운 사진", 불변식 16)
    assertThat(result.record().actions()).hasSize(1);
    assertThat(result.photoBatches()).isEmpty();
    RecordPhoto trace = result.record().photos().getFirst();
    assertThat(trace.file()).isNull();
    assertThat(trace.erased().at()).isEqualTo(T0.plusSeconds(20));
    assertThat(trace.erased().side()).isNull();
  }

  @Test
  void 사진_지문값이_없으면_조용히_넘기지_않고_실패한다() {
    log.add(
        new ActionEntry(
            UUID.randomUUID(),
            room,
            1,
            ActionType.REGISTER,
            PartySide.TENANT,
            member.toString(),
            "k1",
            UUID.randomUUID(),
            ActionTarget.repairCase(UUID.randomUUID()),
            null,
            null,
            null,
            Map.of(
                "photos", List.of(Map.of("photoId", UUID.randomUUID().toString(), "sortOrder", 0))),
            T0));

    assertThatThrownBy(() -> service.download(room, member))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("originalSha256");
  }

  @Test
  void 사진_지문은_payload에서_빼고_다른_payload는_그대로_둔다() {
    log.add(withPhotos(1, T0, List.of(UUID.randomUUID())));

    RoomDownload result = service.download(room, member);

    assertThat(result.record().actions().getFirst().payload()).containsOnlyKeys("answer");
  }

  @Test
  void 호실_기록이_대상인_행동은_대상이_없다() {
    log.add(entry(1, ActionType.MOVE_OUT, PartySide.TENANT, ActionTarget.roomRecord(room), T0));
    log.add(entry(2, ActionType.MOVE_OUT, PartySide.TENANT, null, T0));

    RoomDownload result = service.download(room, member);

    assertThat(result.record().actions()).allSatisfy(a -> assertThat(a.target()).isNull());
    assertThat(result.record().exportedAt()).isEqualTo(T0);
    assertThat(result.record().format()).isEqualTo("homestamp-record/1");
  }

  @Test
  void 묶음은_50장씩이다() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      ids.add(UUID.randomUUID());
    }

    assertThat(DownloadService.batches(ids)).extracting(List::size).containsExactly(50, 50, 1);
    assertThat(DownloadService.batches(List.of())).isEmpty();
  }

  private ActionEntry withPhotos(long seq, Instant at, List<UUID> photoIds) {
    List<Map<String, Object>> photos = new ArrayList<>();
    for (int i = 0; i < photoIds.size(); i++) {
      // 저장 순서와 다르게 넣어도 표시 순서로 놓는다
      int order = i;
      photos.addFirst(
          Map.of("photoId", photoIds.get(i).toString(), "originalSha256", SHA, "sortOrder", order));
    }
    return new ActionEntry(
        UUID.randomUUID(),
        room,
        seq,
        ActionType.REGISTER,
        PartySide.TENANT,
        member.toString(),
        "k" + seq,
        UUID.randomUUID(),
        ActionTarget.repairCase(UUID.randomUUID()),
        null,
        null,
        null,
        Map.of("photos", photos, "answer", "ACK"),
        at);
  }

  private ActionEntry entry(
      long seq, ActionType type, PartySide side, ActionTarget target, Instant at) {
    return new ActionEntry(
        UUID.randomUUID(),
        room,
        seq,
        type,
        side,
        side == null ? "ops:test" : member.toString(),
        "k" + seq,
        UUID.randomUUID(),
        target,
        null,
        null,
        null,
        Map.of(),
        at);
  }
}
