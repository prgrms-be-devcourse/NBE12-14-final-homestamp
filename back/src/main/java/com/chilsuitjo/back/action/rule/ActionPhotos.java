package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 행동 기록만으로 사진을 읽는 순수 함수. 사진 지문은 행동 payload {@code photos}(05 2-3 "사진 지문은 행동 payload에", #107), 지운
 * 사진은 {@code ERASE_PHOTO} 행동에 있다. 그래서 행동 목록을 보이는 조회(내려받기 DL-1, 경과 REP-11)가 {@code photo}를 부르지 않고 같은
 * 방법으로 읽는다.
 */
public final class ActionPhotos {

  private ActionPhotos() {}

  /**
   * 지운 사진의 흔적(불변식 16).
   *
   * @param side 지운 측. 다른 사람의 요청으로 운영이 지웠으면 null
   * @param at 지운 시각
   */
  public record Erased(PartySide side, Instant at) {}

  /** 이 행동으로 붙인 사진. 표시 순서대로. 모양은 {@link PhotoDigest}가 정한다(세 키 모두 있음). 어긋나면 조용히 빼지 않고 실패한다. */
  public static List<PhotoDigest> attached(ActionEntry entry) {
    if (!(entry.payload().get(SavedState.PHOTOS) instanceof List<?> list)) {
      return List.of();
    }
    List<PhotoDigest> photos = new ArrayList<>();
    for (Object item : list) {
      Map<?, ?> photo = (Map<?, ?>) item;
      photos.add(
          new PhotoDigest(
              UUID.fromString((String) Objects.requireNonNull(photo.get("photoId"), "photoId")),
              (String) Objects.requireNonNull(photo.get("originalSha256"), "originalSha256"),
              ((Number) Objects.requireNonNull(photo.get("sortOrder"), "sortOrder")).intValue()));
    }
    photos.sort(Comparator.comparingInt(PhotoDigest::sortOrder));
    return List.copyOf(photos);
  }

  /**
   * {@code ERASE_PHOTO} 행동으로 지운 사진. 지운 사진은 공유 중단 기준 시각 뒤에 지웠어도 양쪽에 고정 표시라(03 3-1 "지운 사진", 불변식 16)
   * 보이는 행동으로 거르기 전 전체 행동을 넘긴다.
   */
  public static Map<UUID, Erased> erased(List<ActionEntry> all) {
    Map<UUID, Erased> erased = new HashMap<>();
    for (ActionEntry entry : all) {
      if (entry.type() == ActionType.ERASE_PHOTO
          && entry.target() != null
          && entry.target().kind() == TargetKind.PHOTO) {
        erased.putIfAbsent(entry.target().id(), new Erased(entry.actorSide(), entry.createdAt()));
      }
    }
    return erased;
  }

  /** 행동 내용에서 사진 지문({@code photos})을 뺀 것. 사진은 따로 보인다. */
  public static Map<String, Object> payloadWithoutPhotos(ActionEntry entry) {
    Map<String, Object> payload = new LinkedHashMap<>(entry.payload());
    payload.remove(SavedState.PHOTOS);
    return payload;
  }
}
