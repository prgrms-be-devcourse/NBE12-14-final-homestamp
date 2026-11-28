package com.chilsuitjo.back.room.dto;

/**
 * 상대 번호 고쳐 다시 초대 요청(06 4-3 {@code POST /api/rooms/{id}/counterpart-phone}, ROOM-4·LINK-4, #437).
 * 검사는 서비스가 한다(번호 모양·동의 문구 판).
 *
 * @param phone 새 번호. 하이픈·공백이 있어도 되고 숫자만 남겨 저장한다(06 4-0 "전화번호"). 필수고, 지금 초대 번호와 같으면 {@code
 *     VALIDATION_FAILED}
 * @param consentVersion 기록 공유 동의 문구 판(06 CONS-2). 받는 사람이 바뀌어 초대처럼 다시 받는다. 없으면 {@code
 *     CONSENT_REQUIRED}
 */
public record CounterpartPhoneRequest(String phone, String consentVersion) {}
