package com.chilsuitjo.back.room.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 부위 추가 요청(06 4-3 {@code POST /api/rooms/{id}/parts}, ROOM-3).
 *
 * @param zone 구역. V7 기본 목록의 6구역({@code 현관}·{@code 방}·{@code 주방}·{@code 욕실}·{@code 설비·가전}·{@code
 *     기타}) 중 하나와 글자까지 같아야 한다
 * @param name 부위 이름. 서비스가 앞뒤 공백을 자르고 1~50자인지 본다(DB 칸 50)
 */
public record AddPartRequest(@NotNull String zone, @NotNull String name) {}
