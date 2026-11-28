package com.chilsuitjo.back.link.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.Objects;

/**
 * 링크 화면에 필요한 호실 쪽 값. 주소는 법정동 코드표(05 {@code region})의 이름만이고 호실 별칭·건물명은 없다(LINK-2a).
 *
 * @param recipientSide 링크를 받은 자리의 측
 * @param settled 정산 완료(자동 마감 포함)
 * @param joinedAway 합류 전 기록(ROOM-6a). 링크를 받은 사람은 볼 수 없다(03 3-1)
 * @param recipientStatus 링크를 받은 자리의 상태(room {@code RoomForLink}, #358). 링크 종류에 따른 판단은 link가 한다
 * @param roomStatus 호실 상태(거주 중·거주 종료·정산 완료)
 */
public record LinkRoom(
    PartySide recipientSide,
    String sido,
    String sigungu,
    String dong,
    boolean settled,
    boolean joinedAway,
    PartyStatus recipientStatus,
    RoomStatus roomStatus) {

  public LinkRoom {
    Objects.requireNonNull(recipientSide, "recipientSide");
    Objects.requireNonNull(recipientStatus, "recipientStatus");
    Objects.requireNonNull(roomStatus, "roomStatus");
  }

  /**
   * 초대 링크(호실 초대·기록 링크, {@code LinkKind.INVITE})를 지금 열 수 있는 자리·호실 상태인지. 받는 자리가 초대됨이고 호실이 거주 중일 때만이다.
   * 거주 종료·정산 완료면 초대 링크 무효(03 3장 상태별 표 "링크"), 연결됨이면 무효(03 C). 거절됨은 #392 A 답 7222021503(거절 때 그 자리의 초대
   * 링크 무효화, Q2의 나머지는 미정)을 따른다. 03 C 무효 사유 목록에는 아직 없고, 거절 때 링크 행 무효화도 구현 전이라 상태로 막는다. 링크 행의 무효화와 함께
   * 본다: 번호를 고쳐 다시 초대하면 자리가 다시 초대됨이라 옛 링크는 이 검사로 막히지 않고 무효화({@code REINVITED})로만 막힌다. 정산 초대 링크에는 쓰지
   * 않는다(거주 종료 뒤 발급하는 링크라 조건이 다르다).
   */
  public boolean opensInvite() {
    return recipientStatus == PartyStatus.INVITED && roomStatus == RoomStatus.LIVING;
  }

  /** 링크로 보여 주면 안 되는 호실 상태. 이유는 링크를 가진 사람에게 알리지 않는다(06 LINK-2, decisions 2026-11-19). */
  public boolean hiddenFromLink() {
    return settled || joinedAway;
  }
}
