package com.chilsuitjo.back.room.dto;

/**
 * 연결 요청(06 4-3 {@code POST /api/invitations/{partyId}/connect}, ROOM-5, CONS-2). 합류할 내 기록
 * 고르기(ROOM-6)는 Should라 아직 받지 않는다.
 *
 * @param consentVersion 기록 공유 동의 문구 판(06 CONS-2). 없으면 {@code CONSENT_REQUIRED}
 */
public record ConnectRequest(String consentVersion) {}
