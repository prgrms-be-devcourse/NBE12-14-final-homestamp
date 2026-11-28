package com.chilsuitjo.back.room.dto;

import java.util.UUID;

/**
 * "제 집이 아니에요" 요청(06 4-3 {@code POST /api/invitations/{partyId}/reject}, ROOM-5b).
 *
 * @param inviteId 받은 초대 목록이 준 초대 회차 ID(#437). 없으면 {@code VALIDATION_FAILED}, 그 뒤 번호를 고쳐 다시 초대해 지금
 *     회차와 다르면 {@code INVALID_STATE}(예전 초대 화면의 늦은 거절이 새 초대를 거절하지 않게, #370 ①)
 */
public record RejectRequest(UUID inviteId) {}
