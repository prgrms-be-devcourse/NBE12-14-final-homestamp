package com.chilsuitjo.back.notification.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 업체에 넘기는 문자 한 통. {@code batchLeadId}는 솔라피 사용자 정의 칸에 넣는 묶음 ID다(05 {@code notice.batch_lead_id}).
 *
 * <p>{@code toString}은 번호·본문을 보여 주지 않는다(로그에 찍혀도 남지 않게).
 */
public record SmsMessage(UUID batchLeadId, String phone, String text) {

  public SmsMessage {
    Objects.requireNonNull(batchLeadId, "batchLeadId");
    Objects.requireNonNull(phone, "phone");
    Objects.requireNonNull(text, "text");
  }

  @Override
  public String toString() {
    return "SmsMessage[batchLeadId=" + batchLeadId + ", phone=***, text=***]";
  }
}
