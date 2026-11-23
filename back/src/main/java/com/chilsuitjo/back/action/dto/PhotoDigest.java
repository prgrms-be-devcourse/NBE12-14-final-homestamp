package com.chilsuitjo.back.action.dto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 행동에 붙은 사진 한 장의 지문(05 2-3 "사진 지문은 행동 payload에", #107 합의). 사진을 붙이는 쪽(photo의 {@code attach})이 이 모양으로
 * 돌려주고, 도메인 {@code save}가 {@link SavedState#withPhotos}로 넘긴다.
 *
 * @param photoId 사진 ID
 * @param originalSha256 원본 파일의 SHA-256. 64자 소문자 hex(DB {@code photo.original_sha256}과 같은 값). 썸네일은
 *     넣지 않는다
 * @param sortOrder 표시 순서. 0 이상
 */
public record PhotoDigest(UUID photoId, String originalSha256, int sortOrder) {

  private static final Pattern LOWER_HEX_64 = Pattern.compile("[0-9a-f]{64}");

  public PhotoDigest {
    Objects.requireNonNull(photoId, "photoId");
    if (originalSha256 == null || !LOWER_HEX_64.matcher(originalSha256).matches()) {
      throw new IllegalArgumentException("사진 지문은 64자 소문자 hex여야 해요: " + originalSha256);
    }
    if (sortOrder < 0) {
      throw new IllegalArgumentException("표시 순서는 0 이상이어야 해요: " + sortOrder);
    }
  }

  /** payload에 넣을 모양. 키 순서는 해시가 정렬하므로 상관없다(05 2-3 payload). */
  Map<String, Object> toPayload() {
    return Map.of(
        "photoId", photoId.toString(), "originalSha256", originalSha256, "sortOrder", sortOrder);
  }
}
