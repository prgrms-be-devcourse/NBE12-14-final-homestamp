package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.room.entity.Region;

/**
 * 호실 주소(법정동). 시·구·동까지만 둔다(06 0-1 "주소 저장 범위").
 *
 * @param sigungu 세종특별자치시는 null
 */
public record RoomRegion(String code, String sido, String sigungu, String dong) {

  public static RoomRegion of(Region region) {
    return new RoomRegion(
        region.getCode(), region.getSido(), region.getSigungu(), region.getDong());
  }
}
