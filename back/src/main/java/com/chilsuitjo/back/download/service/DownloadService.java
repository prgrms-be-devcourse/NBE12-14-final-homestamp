package com.chilsuitjo.back.download.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.rule.ActionPhotos;
import com.chilsuitjo.back.action.rule.ActionVisibility;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.download.dto.RoomDownload;
import com.chilsuitjo.back.download.dto.RoomDownload.ActionPhoto;
import com.chilsuitjo.back.download.dto.RoomDownload.Erased;
import com.chilsuitjo.back.download.dto.RoomDownload.RecordAction;
import com.chilsuitjo.back.download.dto.RoomDownload.RecordFile;
import com.chilsuitjo.back.download.dto.RoomDownload.RecordPhoto;
import com.chilsuitjo.back.download.dto.RoomDownload.Target;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내려받기 목록(06 DL-1). 행동 기록만으로 만든다: 사진 지문값은 행동 payload의 {@code photos}(05 2-3 "사진 지문은 행동 payload에"),
 * 지운 사진은 {@code ERASE_PHOTO} 행동에서 읽는다({@link ActionPhotos}). 그래서 사진 창구를 부르지 않는다. 보이는 범위는
 * 경과(REP-11)와 같은 {@link ActionVisibility}다. 원본 5분 주소는 화면이 묶음마다 {@code POST
 * /api/photos/view-urls}({@code variant: ORIGINAL})로 받는다(#246).
 */
@Service
@RequiredArgsConstructor
public class DownloadService {

  /** 한 번에 주소를 받는 사진 수(06 DL-1b "50장씩"). */
  static final int BATCH_SIZE = 50;

  static final String FORMAT = "homestamp-record/1";
  static final String NOTICE = "서비스에 남긴 기록의 사본";
  static final String PHOTO_DIR = "사진/";
  static final String PHOTO_EXT = ".jpg";

  private final RoomAccessService access;
  private final CounterpartReader parties;
  private final ActionReader actions;
  private final Clock clock;

  /**
   * 그 호실 회원의 내려받기 목록. 당사자가 아니면 {@code NOT_FOUND}다(ROOM-2a). 정산 완료 뒤·거주 종료 뒤·공유를 그만둔 뒤에도 받는다(03 6-3
   * "그동안", 03 B).
   */
  @Transactional(readOnly = true)
  public RoomDownload download(UUID roomRecordId, UUID memberId) {
    RoomAccess viewer = access.require(roomRecordId, memberId);
    RoomParties room = parties.read(roomRecordId, viewer.side());
    ActionVisibility.Cutoff cutoff =
        ActionVisibility.cutoff(
            room.mine().sharingStoppedAt(),
            room.counterpart().sharingStoppedAt(),
            room.counterpart().withdrawn());
    List<ActionEntry> all = actions.findByRoom(roomRecordId);
    List<ActionEntry> visible = ActionVisibility.select(all, viewer.side(), cutoff);

    // 지운 사진은 기준 시각 뒤에 지웠어도 양쪽에 고정 표시다(03 3-1 "지운 사진", 불변식 16). 그래서 걸러지기 전 전체에서 찾는다
    Map<UUID, ActionPhotos.Erased> erased = ActionPhotos.erased(all);
    List<RecordAction> recordActions = new ArrayList<>();
    Map<UUID, RecordPhoto> photos = new LinkedHashMap<>();
    for (ActionEntry entry : visible) {
      List<ActionPhoto> attached = new ArrayList<>();
      for (PhotoDigest ref : ActionPhotos.attached(entry)) {
        Erased trace = trace(erased.get(ref.photoId()));
        String file = trace == null ? PHOTO_DIR + ref.photoId() + PHOTO_EXT : null;
        attached.add(new ActionPhoto(ref.photoId(), ref.originalSha256(), file));
        photos.putIfAbsent(
            ref.photoId(),
            new RecordPhoto(
                ref.photoId(),
                file,
                ref.originalSha256(),
                entry.actorSide(),
                target(entry),
                entry.createdAt(),
                trace));
      }
      recordActions.add(
          new RecordAction(
              entry.id(),
              entry.createdAt(),
              entry.type(),
              entry.actorSide(),
              target(entry),
              entry.reason(),
              ActionPhotos.payloadWithoutPhotos(entry),
              List.copyOf(attached)));
    }

    List<UUID> files =
        photos.values().stream().filter(p -> p.file() != null).map(RecordPhoto::photoId).toList();
    RecordFile record =
        new RecordFile(
            FORMAT,
            clock.instant(),
            NOTICE,
            viewer.side(),
            viewer.roomStatus(),
            List.copyOf(recordActions),
            List.copyOf(photos.values()));
    return new RoomDownload(record, batches(files), files.size());
  }

  static List<List<UUID>> batches(List<UUID> ids) {
    List<List<UUID>> result = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
      result.add(List.copyOf(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()))));
    }
    return List.copyOf(result);
  }

  private static Erased trace(ActionPhotos.Erased erased) {
    return erased == null ? null : new Erased(erased.side(), erased.at());
  }

  private static Target target(ActionEntry entry) {
    if (entry.target() == null || entry.target().kind() == TargetKind.ROOM_RECORD) {
      return null;
    }
    return new Target(entry.target().kind(), entry.target().id());
  }
}
