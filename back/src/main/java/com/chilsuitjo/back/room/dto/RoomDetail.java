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
 * @param moveInDate 입주일. 비어 있을 수 있다(ROOM-1a). <b>남은 한계(입주 점검 후속)</b>: 지금은 행의 값을 그대로 준다. 입주 점검
 *     시작(INSP-2b)이 입주일을 채우게 되면, 공유를 그만둔 뒤 상대가 처음 채운 입주일도 그대로 보여 불변식 18에 걸린다. 그 기능에서 그만둔 시각 뒤 상대가 채운
 *     값은 숨기도록 이 칸을 고친다(#190)
 * @param createdBySide 호실을 만든 측
 * @param myStatus 내 자리 상태
 * @param counterpartStatus 상대 자리 상태
 * @param joinedAway 합류 전 기록(나만 읽음, 03 3-1)
 * @param asOf 공유를 그만둔 시각(두 측 중 이른 쪽). 이 시각 뒤 상대 기록은 보이지 않는다(03 3-1, 불변식 18). 상대가 탈퇴했거나 아무도 그만두지
 *     않았으면 null
 * @param availableActions 지금 누를 수 있는 호실 행동. 이름은 API 끝 단어(06 4-0)
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
    List<String> availableActions) {}
