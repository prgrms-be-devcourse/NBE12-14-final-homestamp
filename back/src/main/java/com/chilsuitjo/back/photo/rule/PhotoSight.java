package com.chilsuitjo.back.photo.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 회원에게 사진 한 장이 보이나(03 3-1 "사진"·"작성 중 점검", 불변식 18). 내가 올린 사진은 늘 보이고, 상대가 올린 사진은 상대에게 보이기 시작한
 * 시각({@code photo_visibility.released_at})이 있고 공유를 그만둔 시각보다 뒤가 아닐 때만 보인다. {@code
 * PhotoCountRepository.countVisible}의 SQL 조건과 같다.
 *
 * <p>상대의 작성 중 점검 사진은 {@code released_at}이 비어 있어서 가려진다(#295). 그 점검에 확인 요청을 보내면 그 시각으로 채워지므로, 공유를 그만둔
 * 뒤 보낸 확인 요청의 사진은 계속 가려진다({@code InspectionSight}와 같은 판단).
 */
public final class PhotoSight {

  private PhotoSight() {}

  /**
   * @param uploadedBy 사진을 올린 측
   * @param releasedAt 상대에게 보이기 시작한 시각. 보통 사진은 붙인 행동의 서버 시각, 작성 중 점검 사진은 확인 요청 시각. 아직 보이지 않으면 null
   * @param viewer 보는 회원의 측
   * @param sharingStoppedAt 두 측 중 이른 공유 중단 시각({@code RoomAccess.sharingStoppedAt}). 상대가 탈퇴해도 남는다.
   *     없으면 null
   */
  public static boolean visibleTo(
      PartySide uploadedBy, Instant releasedAt, PartySide viewer, Instant sharingStoppedAt) {
    if (uploadedBy == viewer) {
      return true;
    }
    return releasedAt != null
        && (sharingStoppedAt == null || !releasedAt.isAfter(sharingStoppedAt));
  }
}
