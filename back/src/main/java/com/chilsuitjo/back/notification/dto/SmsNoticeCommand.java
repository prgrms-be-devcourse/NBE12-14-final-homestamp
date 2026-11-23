package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.NoticeKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 문자 통지 하나를 아웃박스에 넣는 명령.
 *
 * @param roomRecordId 호실 기록(필수)
 * @param recipientPartyId 받는 자리(필수). 번호는 보낼 때 읽는다
 * @param kind 통지 종류(필수)
 * @param responseRequestId 응답 요청 통지면 그 요청, 아니면 null
 * @param linkId 링크를 담는 통지(초대·정산 초대)면 그 링크, 아니면 null
 */
public record SmsNoticeCommand(
    UUID roomRecordId,
    UUID recipientPartyId,
    NoticeKind kind,
    UUID responseRequestId,
    UUID linkId) {

  public SmsNoticeCommand {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    Objects.requireNonNull(kind, "kind");
  }
}
