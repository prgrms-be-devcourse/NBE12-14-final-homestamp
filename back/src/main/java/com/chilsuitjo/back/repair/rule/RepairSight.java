package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RoomParties;
import java.time.Instant;

/**
 * 보는 사람에게 수리 기록이 어디까지 보이나(03 3-1 "모든 줄에 같게", 6-6 9번, 6-4, 불변식 18). 수리 건 상세·원인 설명·진행 상황·혼자 종료가 같은
 * 판단을 쓴다. 내려받기({@code download.rule.RecordVisibility.cutoff})와 같은 기준이다.
 *
 * <ul>
 *   <li>보는 사람 자신의 기록은 늘 보인다
 *   <li>{@code shared}: 제한이 살아 있는 공유 중단 시각 중 이른 것. 그 뒤의 상대·서버 기록은 보이지 않는다. 상대가 탈퇴해 풀린 공유 중단은 보지
 *       않는다(06 CONS-3p·3q)
 *   <li>{@code departed}: 상대가 탈퇴했을 때 남아 있는 공유 중단 시각(누가 그만뒀든) 중 이른 것. 그 뒤의 <b>상대 측</b> 기록은 계속 보이지
 *       않는다. 중단 동안 탈퇴자가 남긴 기록은 상대가 본 적이 없어 숨기고 지운다(03 6-4). 제한이 풀리는 것은 남은 사람이 할 수 있는 일이고, 그 기록을 보여
 *       준다는 뜻이 아니다(#288). 탈퇴 처리는 {@code party.sharing_stopped_at}을 지우지 않는다는 전제다(decisions
 *       2026-11-26)
 * </ul>
 *
 * <p>둘은 함께 있지 않다: {@code shared}는 상대가 탈퇴하지 않았을 때만, {@code departed}는 탈퇴했을 때만 생긴다.
 *
 * @param viewer 보는 사람의 측. 운영이면 null
 */
public record RepairSight(PartySide viewer, Instant shared, Instant departed) {

  /** 보는 사람 측에서 읽은 두 자리로 정한다({@code CounterpartReader.read(room, viewer)}). */
  public static RepairSight of(RoomParties room, PartySide viewer) {
    Instant shared =
        earliest(
            room.mySharingStoppedApplies() ? room.mine().sharingStoppedAt() : null,
            room.counterpartSharingStoppedApplies() ? room.counterpart().sharingStoppedAt() : null);
    Instant departed =
        room.counterpart().withdrawn()
            ? earliest(room.mine().sharingStoppedAt(), room.counterpart().sharingStoppedAt())
            : null;
    return new RepairSight(viewer, shared, departed);
  }

  /** 보이는 범위를 고정한 시각(화면의 "○월 ○일 기준"). 고정하지 않았으면 null. */
  public Instant asOf() {
    return shared != null ? shared : departed;
  }

  /**
   * 이 측이 이 시각에 남긴 기록이 보이나.
   *
   * @param side 남긴 측. 서버 기록이면 null
   */
  public boolean sees(PartySide side, Instant at) {
    if (side != null && side == viewer) {
      return true;
    }
    if (shared != null && at.isAfter(shared)) {
      return false;
    }
    // 여기까지 온 측 있는 기록은 상대 것이다
    return side == null || departed == null || !at.isAfter(departed);
  }

  private static Instant earliest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isBefore(b) ? a : b;
  }
}
