package com.chilsuitjo.back.notification.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 전달 결과를 묻기로 가져간 통지 하나(#120). 가져가는 트랜잭션은 이미 커밋됐으므로 이 값만 들고 트랜잭션 밖에서 업체에 묻는다.
 *
 * @param noticeId 결과를 적을 통지
 * @param query 업체에 물을 값
 * @param pastDeadline 가져갈 때 이미 보낸 뒤 기한(3일)이 지났는지. 참이면 이번이 마지막 조회다(결과가 없으면 최종 실패로 끝낸다, 3-B)
 * @param lease 가져가며 DB에 적은 {@code next_poll_at}(마이크로초까지, DB 값과 같음). 결과를 적을 때 지금 값과 다르면 그사이 다른 작업자가
 *     다시 가져간 것이다(가져갈 때마다 값이 커지므로 같은 값이 다시 쓰이지 않음)
 */
public record ClaimedDeliveryPoll(
    UUID noticeId, DeliveryQuery query, boolean pastDeadline, Instant lease) {

  public ClaimedDeliveryPoll {
    Objects.requireNonNull(noticeId, "noticeId");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(lease, "lease");
  }
}
