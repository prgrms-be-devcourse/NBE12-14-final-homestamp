package com.chilsuitjo.back.room.dto;

import com.chilsuitjo.back.global.common.TargetKind;
import java.util.UUID;

/**
 * 상대 초대 요청(06 4-3 {@code POST /api/rooms/{id}/invite}, ROOM-4, CONS-2). 검사는 서비스가 한다(번호 모양·동의 문구
 * 판·기록 종류).
 *
 * @param phone 초대할 번호. 하이픈·공백이 있어도 되고 숫자만 남겨 저장한다(06 4-0 "전화번호")
 * @param consentVersion 기록 공유 동의 문구 판(06 CONS-2). 없으면 {@code CONSENT_REQUIRED}
 * @param record 기록 링크면 그 기록, 호실 초대 화면이면 없음
 */
public record InviteRequest(String phone, String consentVersion, SharedRecord record) {

  /**
   * 기록 링크로 공유할 기록. 지금은 점검({@code INSPECTION})만 받는다(#163).
   *
   * @param kind 기록 종류
   * @param id 기록 ID
   */
  public record SharedRecord(TargetKind kind, UUID id) {}
}
