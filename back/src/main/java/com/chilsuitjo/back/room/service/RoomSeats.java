package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.rule.PartyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 호실 기록과 두 당사자 자리. 권한 검사({@link JpaRoomAccessService})와 pipeline 잠금({@link JpaRoomGateReader})이
 * "이 회원이 어느 자리인가"를 같은 방법으로 정하게 모아 둔다.
 */
record RoomSeats(RoomRecord room, List<Party> parties) {

  /**
   * 이 회원의 자리. 이 호실의 당사자가 아니면 {@code NOT_FOUND}다(06 ROOM-2a: 남의 호실은 있는지조차 알 수 없다). 탈퇴한 자리도 당사자가 아니다:
   * 탈퇴 뒤 만료 전 액세스 토큰이 남아 있어도 호실을 읽거나 행동할 수 없다(03 L "토큰 폐기", 06 KEEP-4).
   */
  Party seatOf(UUID memberId) {
    return parties.stream()
        .filter(party -> memberId.equals(party.getMemberId()))
        .filter(party -> party.getStatus() != PartyStatus.WITHDRAWN)
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  /** 이 자리의 반대쪽 자리. 호실을 만들 때 두 자리를 함께 만들므로 늘 있다. */
  Party counterpartOf(Party mine) {
    return parties.stream()
        .filter(party -> party.getSide() != mine.getSide())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("반대쪽 자리가 없는 호실이에요: " + room.getId()));
  }

  /** 두 측 중 공유를 그만둔 이른 시각. 아무도 그만두지 않았으면 null(05 불변식 18). */
  Instant earliestSharingStoppedAt() {
    return parties.stream()
        .map(Party::getSharingStoppedAt)
        .filter(Objects::nonNull)
        .min(Instant::compareTo)
        .orElse(null);
  }

  /**
   * 이 회원에게 보이는 범위를 고정하는 시각(03 6-6 9번, 3-1 "모든 줄에 같게", 불변식 18). 두 측 중 이른 공유 중단 시각이고, 상대가 탈퇴했으면 함께 볼
   * 상대가 없어 고정하지 않는다(null). 수리 건의 {@code RepairStateStore.sharingCutoff}와 같은 기준이다(#175).
   */
  Instant visibleCutoffFor(Party mine) {
    if (counterpartOf(mine).getStatus() == PartyStatus.WITHDRAWN) {
      return null;
    }
    return earliestSharingStoppedAt();
  }

  /**
   * 상대가 탈퇴했을 때 그 측 기록을 계속 빼는 시각: 남아 있는 공유 중단 시각(누가 그만뒀든) 중 이른 것. 상대가 탈퇴하지 않았거나 아무도 그만둔 적이 없으면
   * null이다. 중단 동안 탈퇴자가 남긴 기록은 상대가 본 적이 없어 숨긴다(03 6-4, #288). 상태는 다시 계산하지 않고 보이는지만 가른다(수리 {@code
   * RepairSight.departed}, 경과·내려받기 {@code ActionVisibility.Cutoff.departed}와 같은 기준).
   */
  Instant departedCutoffFor(Party mine) {
    if (counterpartOf(mine).getStatus() != PartyStatus.WITHDRAWN) {
      return null;
    }
    return earliestSharingStoppedAt();
  }

  /**
   * 이 회원이 공유를 그만둔 채라 막혀야 하는지. 상대가 탈퇴했으면 함께 볼 상대가 없어 제한이 풀린다(03 B, 06 CONS-3p). 판단은 상대 자리 상태로 하고 남아
   * 있는 {@code sharing_stopped_at}은 보지 않는다.
   */
  boolean sharingStoppedFor(Party mine) {
    return mine.getStatus() == PartyStatus.SHARING_STOPPED
        && counterpartOf(mine).getStatus() != PartyStatus.WITHDRAWN;
  }
}
