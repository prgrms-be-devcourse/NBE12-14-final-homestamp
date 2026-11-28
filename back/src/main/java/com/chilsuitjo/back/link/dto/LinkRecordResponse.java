package com.chilsuitjo.back.link.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.photo.dto.ErasedPhoto;
import com.chilsuitjo.back.photo.rule.EraseReason;
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
 * @param items 점검 항목(점검일 때만, 더한 순서). 수리 건이면 빈 목록이다. 항목마다 부위·설명·응답 상태·사진을 묶어 어느 사진이 어느 항목의 것인지 남긴다.
 *     항목 ID는 내보내지 않는다(링크 열람자는 항목을 가리킬 일이 없음, LINK-1b. #303 D 결정 7218475215)
 */
public record LinkRecordResponse(
    TargetKind targetKind,
    String partName,
    String title,
    String description,
    List<String> statusLines,
    List<Photo> photos,
    List<HistoryItem> history,
    List<Item> items) {

  public LinkRecordResponse {
    statusLines = List.copyOf(statusLines);
    photos = List.copyOf(photos);
    history = List.copyOf(history);
    items = List.copyOf(items);
  }

  /** 항목이 없는 기록(수리 건). */
  public LinkRecordResponse(
      TargetKind targetKind,
      String partName,
      String title,
      String description,
      List<String> statusLines,
      List<Photo> photos,
      List<HistoryItem> history) {
    this(targetKind, partName, title, description, statusLines, photos, history, List.of());
  }

  /**
   * 점검 항목 하나(#303).
   *
   * @param partName 부위 이름(숨긴 부위도 지난 기록이면 보임)
   * @param description 이 측에게 보이는 판의 설명
   * @param statusLine 이 측에게 보이는 응답 상태 한 줄(06 부록 A 말)
   * @param photos 이 항목의 사진. 링크에서 숨긴 사진은 없고 주소는 5분 동안만 열린다
   */
  public record Item(String partName, String description, String statusLine, List<Photo> photos) {

    public Item {
      photos = List.copyOf(photos);
    }
  }

  /**
   * 사진 하나. 지운 사진은 주소 없이 흔적으로만 보인다(03 6-1 "잘못 올린 사진", 불변식 16): 올린 사람이 지웠으면 "지운 사진 · 측 · 시각", 다른 사람의
   * 요청으로 지웠으면 측 없이 사유와 시각. 회원 화면({@code POST /api/photos/view-urls}의 {@code erased})과 같은 값이다.
   *
   * @param erased 지운 사진인지. 화면이 이미 쓰는 칸이라 그대로 두고 아래 흔적 칸과 함께 채운다
   * @param erasedAt 지운 시각. 지우지 않았으면 null
   * @param erasedSide 지운 측(= 올린 측). 다른 사람의 요청({@code THIRD_PARTY})으로 지웠거나 지우지 않았으면 null
   * @param erasedReason 지운 사유. 지우지 않았으면 null
   */
  public record Photo(
      UUID id,
      String thumbUrl,
      String originalUrl,
      Instant expiresAt,
      boolean erased,
      Instant erasedAt,
      PartySide erasedSide,
      EraseReason erasedReason) {

    public Photo {
      if (erased != (erasedAt != null) || erased != (erasedReason != null)) {
        throw new IllegalArgumentException("지운 사진이면 지운 시각과 사유가 있어야 하고, 아니면 없어야 해요");
      }
      if (!erased && erasedSide != null) {
        throw new IllegalArgumentException("지우지 않은 사진에는 지운 측이 없어요");
      }
      if (erasedReason == EraseReason.THIRD_PARTY && erasedSide != null) {
        throw new IllegalArgumentException("다른 사람의 요청으로 지운 사진은 측을 보이지 않아요(불변식 16)");
      }
      if (erased && (thumbUrl != null || originalUrl != null)) {
        throw new IllegalArgumentException("지운 사진은 파일이 없어 주소를 담지 않아요");
      }
    }

    /** 지우지 않은 사진. */
    public static Photo shown(UUID id, String thumbUrl, String originalUrl, Instant expiresAt) {
      return new Photo(id, thumbUrl, originalUrl, expiresAt, false, null, null, null);
    }

    /** 지운 사진. 주소 없이 흔적(측·시각·사유)만 담는다. */
    public static Photo erased(UUID id, ErasedPhoto trace) {
      return new Photo(id, null, null, null, true, trace.at(), trace.side(), trace.reason());
    }
  }

  /** 경과 한 줄. */
  public record HistoryItem(String label, PartySide side, Instant at) {}
}
