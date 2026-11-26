package com.chilsuitjo.back.notification.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 전달 결과를 물을 문자 한 통(04 4-2). 결과는 {@code batchLeadId}로 돌려받는다.
 *
 * @param batchLeadId 그 문자의 묶음 ID(05 {@code notice.batch_lead_id}, 보낼 때 업체 사용자 정의 칸에 넣은 값). 결과를 통지에
 *     맞추는 키
 * @param providerMessageId 업체가 보낸 뒤 돌려준 메시지 ID(05 {@code notice.provider_message_id}). 결과를 모른 채
 *     보냈으면 null. 업체에 어떤 키로 물을지는 실제 업체 구현이 정한다. 사용자 정의 칸 값으로 검색되는지는 아직 확인하지 않았다(#120, NOTI-11)
 */
public record DeliveryQuery(UUID batchLeadId, String providerMessageId) {

  public DeliveryQuery {
    Objects.requireNonNull(batchLeadId, "batchLeadId");
  }
}
