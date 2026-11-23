package com.chilsuitjo.back.room.rule;

/**
 * {@link RoomTransitions}가 다루는 호실 기록 상태(03 A).
 *
 * @param status 호실 상태. 아직 만들지 않은 호실이면 null
 */
public record RoomState(RoomStatus status) {}
