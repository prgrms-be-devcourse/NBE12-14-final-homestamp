package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.NextStep;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 내 호실 목록의 한 줄(06 4-3 {@code GET /api/rooms}, S-03 호실 카드).
 *
 * @param mySide 이 호실에서 내 측. 화면이 "사는 집"·"관리하는 호실"로 나눈다
 * @param counterpartStatus 상대 자리 상태
 * @param joinedAway 합류 전 기록(나만 읽음, 03 3-1)
 * @param nextStep 다음 할 일 한 줄
 */
public record RoomSummary(
    UUID id,
    String alias,
    RoomRegion region,
    RoomStatus status,
    PartySide mySide,
    PartyStatus counterpartStatus,
    boolean joinedAway,
    NextStep nextStep,
    Instant createdAt) {}
