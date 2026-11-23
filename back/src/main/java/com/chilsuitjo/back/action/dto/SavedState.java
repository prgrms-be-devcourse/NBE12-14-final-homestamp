package com.chilsuitjo.back.action.dto;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code StateStore.save}의 결과. 저장해 봐야 알 수 있는 값을 pipeline에 돌려준다.
 *
 * @param target 새로 만든 대상(REGISTER 등). 호실을 만드는 요청의 첫 단계면 새 호실({@code ActionTarget.roomRecord}). 원래
 *     대상 그대로면 null
 * @param content 새로 만든 판의 지문 재료(판 ID가 저장 뒤에 생기므로). 바뀌지 않았으면 null
 * @param payload 저장해 봐야 생기는 작은 값. pipeline이 요청의 payload에 더해 행동 기록에 남긴다(행동 해시에 들어감). 대표는 {@code
 *     save}에서 붙인 사진 목록이다(05 2-3 "사진 지문은 행동 payload에", #141). 요청 payload와 같은 키면 pipeline이 거절한다. 없으면
 *     빈 맵
 */
public record SavedState(ActionTarget target, DigestInput content, Map<String, Object> payload) {

  /** 사진 목록을 담는 payload 키. 값은 {@link #withPhotos}가 만든다. */
  public static final String PHOTOS = "photos";

  public SavedState {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public SavedState(ActionTarget target, DigestInput content) {
    this(target, content, Map.of());
  }

  public static SavedState unchanged() {
    return new SavedState(null, null);
  }

  public static SavedState created(ActionTarget target) {
    return new SavedState(target, null);
  }

  /** 같은 대상·판에 payload 값을 더한다. 같은 키가 이미 있으면 부르는 쪽 실수라 거절한다. */
  public SavedState withPayload(String key, Object value) {
    if (payload.containsKey(key)) {
      throw new IllegalArgumentException("payload에 이미 " + key + "가 있어요");
    }
    Map<String, Object> next = new LinkedHashMap<>(payload);
    next.put(key, value);
    return new SavedState(target, content, next);
  }

  /**
   * {@code save}에서 붙인 사진들을 payload {@value #PHOTOS}에 넣는다(#107 합의). 한 장마다 {@code photoId}·{@code
   * originalSha256}(64자 소문자 hex)·{@code sortOrder}이고, 순서는 {@code (sortOrder, photoId)} 오름차순이다. 썸네일
   * 지문은 넣지 않는다(원본만). 어떤 순서로 줘도 같은 목록이 되게 여기서 정렬한다(행동 해시 입력이 흔들리지 않게). 사진이 없으면 칸을 넣지 않는다.
   */
  public SavedState withPhotos(List<PhotoDigest> photos) {
    if (photos.isEmpty()) {
      return this;
    }
    List<Map<String, Object>> list =
        photos.stream()
            .sorted(
                Comparator.comparingInt(PhotoDigest::sortOrder).thenComparing(PhotoDigest::photoId))
            .map(PhotoDigest::toPayload)
            .toList();
    return withPayload(PHOTOS, list);
  }
}
