package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 이 회원이 이 호실에서 무엇인지. 다른 도메인과 주고받는 값이다.
 *
 * @param roomRecordId 호실 기록
 * @param memberId 행동의 주체
 * @param partyId 내 당사자 자리
 * @param side 내 측
 * @param myStatus 내 자리 상태. 공유를 그만둔 사람의 확인을 막을 때 쓴다(CONS-3b)
 * @param roomStatus 호실 상태
 * @param joinedIntoRoomId 합류 전 기록이면 합류한 호실 기록. 아니면 null
 * @param counterpartStatus 상대 자리 상태
 * @param sharingStoppedAt 공유를 그만둔 시각(두 측 중 이른 쪽, 없으면 null). 불변식 18에 쓴다
 */
public record RoomAccess(
    UUID roomRecordId,
    UUID memberId,
    UUID partyId,
    PartySide side,
    PartyStatus myStatus,
    RoomStatus roomStatus,
    UUID joinedIntoRoomId,
    PartyStatus counterpartStatus,
    Instant sharingStoppedAt) {}
