package com.chilsuitjo.back.notification.rule;

import java.util.Objects;
import java.util.UUID;

/**
 * 재통지 행동({@code RENOTIFY})의 상태 값: 호실을 잠근 뒤 정한 재통지 한 통(받는 자리·링크). {@code RenotifyStateStore.load}가
 * 만들고 {@code save}가 이대로 통지를 넣는다.
 *
 * @param linkId 재통지에 담을 링크. 그 자리에 닿은 요청 통지의 링크이고, 그 통지에 링크가 없었으면 null
 */
public record RenotifyPlan(UUID roomRecordId, UUID requestId, UUID recipientPartyId, UUID linkId) {

  public RenotifyPlan {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(recipientPartyId, "recipientPartyId");
  }
}
