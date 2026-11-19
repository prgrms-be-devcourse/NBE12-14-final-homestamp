package com.chilsuitjo.back.link.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 링크로 공유한 기록 하나(LINK-1a: 사진·설명·처리 상태·시간·고친 이력). 링크에서 숨긴 사진·돈의 내용·별칭·이름·번호는 없다.
 *
 * @param targetKind {@code REPAIR_CASE} 또는 {@code INSPECTION}
 * @param partName 부위 이름(숨긴 부위도 지난 기록이면 보임)
 * @param statusLines 처리 상태 한 줄씩(화면 문구는 08)
 * @param photos 링크에서 숨기지 않은 사진. 주소는 5분 동안만 열린다
 * @param history 시간 순 경과(고친 이력 포함). 누가는 측으로만
 */
public record LinkRecordResponse(
    TargetKind targetKind,
    String partName,
    String title,
    String description,
    List<String> statusLines,
    List<Photo> photos,
    List<HistoryItem> history) {

  public LinkRecordResponse {
    statusLines = List.copyOf(statusLines);
    photos = List.copyOf(photos);
    history = List.copyOf(history);
  }

  /** 사진 하나. 지운 사진은 주소 없이 "지운 사진"으로만 보인다(03 6-6). */
  public record Photo(
      UUID id, String thumbUrl, String originalUrl, Instant expiresAt, boolean erased) {}

  /** 경과 한 줄. */
  public record HistoryItem(String label, PartySide side, Instant at) {}
}
