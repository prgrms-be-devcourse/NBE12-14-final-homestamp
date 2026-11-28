package com.chilsuitjo.back.room.dto;

import java.util.UUID;

/**
 * 연결 요청(06 4-3 {@code POST /api/invitations/{partyId}/connect}, ROOM-5, CONS-2). 합류할 내 기록
 * 고르기(ROOM-6)는 Should라 아직 받지 않는다.
 *
 * @param consentVersion 기록 공유 동의 문구 판(06 CONS-2). 없으면 {@code CONSENT_REQUIRED}
 * @param inviteId 받은 초대 목록이 준 초대 회차 ID(#437). 없으면 {@code VALIDATION_FAILED}, 그 뒤 번호를 고쳐 다시 초대해 지금
 *     회차와 다르면 {@code INVALID_STATE}
 */
public record ConnectRequest(String consentVersion, UUID inviteId) {}
