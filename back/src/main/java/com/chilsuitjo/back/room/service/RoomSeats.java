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

  /** 이 회원의 자리. 이 호실의 당사자가 아니면 {@code NOT_FOUND}다(06 ROOM-2a: 남의 호실은 있는지조차 알 수 없다). */
  Party seatOf(UUID memberId) {
    return parties.stream()
        .filter(party -> memberId.equals(party.getMemberId()))
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
   * 이 회원이 공유를 그만둔 채라 막혀야 하는지. 상대가 탈퇴했으면 함께 볼 상대가 없어 제한이 풀린다(03 B, 06 CONS-3p). 판단은 상대 자리 상태로 하고 남아
   * 있는 {@code sharing_stopped_at}은 보지 않는다.
   */
  boolean sharingStoppedFor(Party mine) {
    return mine.getStatus() == PartyStatus.SHARING_STOPPED
        && counterpartOf(mine).getStatus() != PartyStatus.WITHDRAWN;
  }
}
