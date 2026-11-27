package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 이 회원이 당사자인 호실 하나(홈 {@code GET /api/me/home}이 호실마다 읽을 범위, #369·#370 A-8). 다른 도메인과 주고받는 값이다.
 *
 * @param roomRecordId 호실 기록
 * @param mySide 이 호실에서 내 측
 * @param status 호실 상태. 정산 완료 호실을 뺄지는 부르는 쪽이 정한다
 * @param joinedAway 합류 전 기록(나만 읽음, 03 3-1). 내 호실 목록의 {@code joinedAway}와 같다
 * @param sharingStopped 공유 중단 제한이 살아 있는지. **누가 그만뒀든** 참이고, 상대가 탈퇴했으면 제한이 풀려 거짓이다(03 B, 06 CONS-3p).
 *     {@code asOf != null}과 같다. 내가 그만뒀는지만 보는 {@code RoomGate.mySharingStopped}와 다르다
 * @param asOf 이 회원에게 보이는 범위를 고정하는 시각(두 측 중 이른 공유 중단 시각, 불변식 18). 호실 상세의 {@code asOf}와 같다. 제한이 없으면
 *     null. 탈퇴를 보지 않는 {@code RoomAccess.sharingStoppedAt}과 달리 상대가 탈퇴했으면 null이다
 */
public record MemberRoom(
    UUID roomRecordId,
    PartySide mySide,
    RoomStatus status,
    boolean joinedAway,
    boolean sharingStopped,
    Instant asOf) {}
