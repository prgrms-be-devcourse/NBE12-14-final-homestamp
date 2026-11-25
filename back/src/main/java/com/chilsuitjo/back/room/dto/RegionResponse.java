package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.room.entity.Region;

/**
 * 동 이름으로 찾은 법정동 한 줄(06 4-3 {@code GET /api/regions/search}).
 *
 * @param code 법정동 코드 10자리. 호실을 만들 때 {@code regionCode}로 보낸다
 * @param sigungu 세종특별자치시처럼 시·군·구가 없으면 null
 */
public record RegionResponse(String code, String sido, String sigungu, String dong) {

  public static RegionResponse from(Region region) {
    return new RegionResponse(
        region.getCode(), region.getSido(), region.getSigungu(), region.getDong());
  }
}
