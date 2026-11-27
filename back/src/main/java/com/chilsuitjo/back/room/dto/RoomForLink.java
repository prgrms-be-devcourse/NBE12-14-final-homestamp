package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.Objects;

/**
 * 링크를 받은 자리에서 본 호실(#303, #337). 링크 열람자는 회원이 아니라서 회원 권한 검사 없이 읽는다.
 *
 * <p>주소는 법정동 코드표의 시·구·동까지만이고 호실 별칭·건물명·번호·회원 ID는 담지 않는다(06 LINK-2a, LINK-1i). 링크 종류에 따른 판단(거주 종료 뒤
 * 일반 초대 링크를 막는 것 등)은 하지 않고 상태만 준다. 그 판단은 링크 종류를 아는 {@code link}가 한다.
 *
 * @param recipientSide 링크를 받은 자리의 측
 * @param region 호실 주소(시·구·동). 세종은 {@code sigungu}가 null
 * @param recipientStatus 링크를 받은 자리의 상태
 * @param roomStatus 호실 상태(거주 중·거주 종료·정산 완료)
 * @param settled 정산 완료(자동 마감 포함). 호실 단위 거절 {@code SETTLED}와 같은 기준
 * @param joinedAway 합류 전 기록(ROOM-6a). 호실 단위 거절 {@code JOINED_RECORD}와 같은 기준
 */
public record RoomForLink(
    PartySide recipientSide,
    RoomRegion region,
    PartyStatus recipientStatus,
    RoomStatus roomStatus,
    boolean settled,
    boolean joinedAway) {

  public RoomForLink {
    Objects.requireNonNull(recipientSide, "recipientSide");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(recipientStatus, "recipientStatus");
    Objects.requireNonNull(roomStatus, "roomStatus");
  }
}
