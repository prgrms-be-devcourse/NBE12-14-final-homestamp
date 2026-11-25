package com.chilsuitjo.back.room.dto;

import java.util.UUID;

/**
 * 부위 하나(06 4-3 {@code GET /api/rooms/{id}/parts}와 부위 행동의 응답).
 *
 * @param id 부위 ID
 * @param zone 구역(V7 6구역 중 하나)
 * @param name 부위 이름
 * @param enabled 켬 여부(설비·가전이 이 집에 있는지). 숨김과 다르다
 * @param hidden 숨긴 부위인지. 기본 화면 목록에서는 빼고 다시 보이기를 고르는 곳에서만 보인다(ROOM-3a)
 * @param toggleable 켜고 끌 수 있는 부위인지(기본 목록의 설비·가전만)
 */
public record RoomPartView(
    UUID id, String zone, String name, boolean enabled, boolean hidden, boolean toggleable) {}
