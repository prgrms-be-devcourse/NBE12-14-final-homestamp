package com.chilsuitjo.back.room.dto;

import java.time.Instant;
import java.util.List;

/**
 * 부위 목록 응답(06 4-3 {@code GET /api/rooms/{id}/parts}).
 *
 * @param asOf 공유를 그만둔 뒤라 보이는 범위를 고정한 시각(화면의 "○월 ○일 기준", 03 6-6 9번). 고정하지 않으면 null
 * @param parts 숨긴 부위 포함. 구역 순서(V7) 다음 호실 안 순서
 */
public record RoomPartList(Instant asOf, List<RoomPartView> parts) {}
