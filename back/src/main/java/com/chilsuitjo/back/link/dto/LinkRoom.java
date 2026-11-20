package com.chilsuitjo.back.link.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.Objects;

/**
 * 링크 화면에 필요한 호실 쪽 값. 주소는 법정동 코드표(05 {@code region})의 이름만이고 호실 별칭·건물명은 없다(LINK-2a).
 *
 * @param recipientSide 링크를 받은 자리의 측
 * @param settled 정산 완료(자동 마감 포함)
 * @param joinedAway 합류 전 기록(ROOM-6a). 링크를 받은 사람은 볼 수 없다(03 3-1)
 */
public record LinkRoom(
    PartySide recipientSide,
    String sido,
    String sigungu,
    String dong,
    boolean settled,
    boolean joinedAway) {

  public LinkRoom {
    Objects.requireNonNull(recipientSide, "recipientSide");
  }

  /** 링크로 보여 주면 안 되는 호실 상태. 이유는 링크를 가진 사람에게 알리지 않는다(06 LINK-2, decisions 2026-11-19). */
  public boolean hiddenFromLink() {
    return settled || joinedAway;
  }
}
