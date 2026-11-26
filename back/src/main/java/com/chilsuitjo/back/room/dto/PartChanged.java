package com.chilsuitjo.back.room.dto;

/**
 * 부위 행동 결과.
 *
 * @param part 행동 뒤 부위
 * @param replayed 같은 키로 다시 온 요청이라 새로 남긴 행동이 없는지(06 4-0)
 */
public record PartChanged(RoomPartView part, boolean replayed) {}
