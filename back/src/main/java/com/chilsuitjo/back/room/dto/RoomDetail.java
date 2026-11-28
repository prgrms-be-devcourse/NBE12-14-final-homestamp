package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 호실 상세(06 4-3 {@code GET /api/rooms/{id}}, S-06 요약·상대 자리 카드).
 *
 * @param moveInDate 입주일. 비어 있을 수 있다(ROOM-1a). 비어 있던 입주일은 입주 점검 시작이 채운다(INSP-2b). 공유를 그만둔 시각({@code
 *     asOf}) 뒤 상대가 채운 입주일은 비어 있는 것으로 준다(불변식 18, #190)
 * @param createdBySide 호실을 만든 측
 * @param myStatus 내 자리 상태
 * @param counterpartStatus 상대 자리 상태
 * @param joinedAway 합류 전 기록(나만 읽음, 03 3-1)
 * @param asOf 공유를 그만둔 시각(두 측 중 이른 쪽). 이 시각 뒤 상대 기록은 보이지 않는다(03 3-1, 불변식 18). 상대가 탈퇴했거나 아무도 그만두지
 *     않았으면 null
 * @param availableActions 지금 누를 수 있는 호실 행동. 이름은 API 끝 단어(06 4-0)
 * @param counterpartMaskedPhone 상대 자리에 저장된 번호의 가린 모양({@code 010-****-5678}). 보는 사람이 호실을 만든 측이고 번호가
 *     남아 있을 때(번호만·초대됨·거절됨)만 있다. 초대 화면에서 저장한 번호를 초대 전에 확인하게 한다(#407 리뷰). 원래 번호는 주지 않는다
 * @param endRequest 집주인 측이 연 가장 최근 거주 종료 요청과 세입자의 답. 없으면 null. 공유를 그만둔 뒤에도 양쪽에 보인다({@link
 *     RoomEndView})
 * @param endedAt 거주 종료 시각. 거주 중이면 null. 공유를 그만둔 뒤에도 양쪽에 보인다(불변식 18의 예외, decisions 2026-11-17)
 * @param endSoloReason 집주인 측이 혼자 남긴 거주 종료면 그 이유({@code MOVE_OUT} payload {@code solo_reason}), 아니면
 *     null
 */
public record RoomDetail(
    UUID id,
    String alias,
    RoomRegion region,
    LocalDate moveInDate,
    RoomStatus status,
    PartySide mySide,
    PartySide createdBySide,
    PartyStatus myStatus,
    PartyStatus counterpartStatus,
    boolean joinedAway,
    Instant asOf,
    Instant createdAt,
    List<String> availableActions,
    String counterpartMaskedPhone,
    RoomEndView.EndRequest endRequest,
    Instant endedAt,
    String endSoloReason) {}
