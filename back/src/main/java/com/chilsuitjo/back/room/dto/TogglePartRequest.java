package com.chilsuitjo.back.room.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 설비·가전 켜고 끄기 요청(06 4-3 {@code POST /api/rooms/{id}/parts/{partId}/toggle}). 뒤집기가 아니라 원하는 값을 보낸다. 두
 * 사람이 같이 눌러도 서로 상쇄되지 않게 하려는 것이다.
 *
 * @param enabled 켤지(이 집에 있음) 끌지(없음)
 */
public record TogglePartRequest(@NotNull Boolean enabled) {}
