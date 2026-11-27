package com.chilsuitjo.back.repair.dto;

import java.util.List;
import java.util.UUID;

/**
 * 사진 추가(06 REP-7g)의 결과.
 *
 * @param photoIds 붙인 사진(업로드 표 순서). 같은 키로 다시 온 요청이면 처음 붙인 사진이다
 * @param replayed 다시 온 요청인지. 응답 본문에는 넣지 않고 {@code Idempotent-Replayed} 머리로 알린다
 */
public record PhotosAdded(List<UUID> photoIds, boolean replayed) {

  public PhotosAdded {
    photoIds = List.copyOf(photoIds);
  }
}
