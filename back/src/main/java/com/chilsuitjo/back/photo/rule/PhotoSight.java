package com.chilsuitjo.back.photo.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 회원에게 사진 한 장이 보이나(03 3-1 "사진", 불변식 18). 사진 칸만 본다: 내가 올린 사진은 늘 보이고, 상대가 올린 사진은 공유를 그만둔 시각 뒤의 것만 안
 * 보인다. {@code PhotoCountRepository.countVisible}의 SQL 조건과 같다.
 *
 * <p><b>보지 않는 것</b>: 사진이 붙은 대상이 가려졌는지(상대의 작성 중 점검, 03 3-1 "작성 중 점검"). 그 사진의 ID를 내주는 쪽({@code
 * cards}에는 보이는 대상만 넘기고, 내려받기 #274는 보이는 기록만 고른다)이 빼 준다는 전제다. 상대의 작성 중 점검 사진은 #295에서 공개 상태 칸으로 가린다.
 */
public final class PhotoSight {

  private PhotoSight() {}

  /**
   * @param uploadedBy 사진을 올린 측
   * @param createdAt 사진을 붙인 행동의 서버 시각(행동 기록의 {@code created_at}과 같은 값)
   * @param viewer 보는 회원의 측
   * @param sharingStoppedAt 두 측 중 이른 공유 중단 시각({@code RoomAccess.sharingStoppedAt}). 상대가 탈퇴해도 남는다.
   *     없으면 null
   */
  public static boolean visibleTo(
      PartySide uploadedBy, Instant createdAt, PartySide viewer, Instant sharingStoppedAt) {
    return uploadedBy == viewer || sharingStoppedAt == null || !createdAt.isAfter(sharingStoppedAt);
  }
}
