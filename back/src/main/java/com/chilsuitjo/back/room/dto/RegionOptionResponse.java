package com.chilsuitjo.back.room.dto;

/**
 * 단계 선택 한 칸(06 4-3 {@code GET /api/regions?parent=}).
 *
 * @param code 시·도 2자리, 시·군·구 5자리, 읍·면·동 10자리
 * @param name 시·도·시·군·구·읍·면·동 이름. 세종특별자치시의 시·군·구({@code 36110})는 시·군·구가 없어 null이다(화면이 그 단계를 건너뛴다)
 */
public record RegionOptionResponse(String code, String name) {}
