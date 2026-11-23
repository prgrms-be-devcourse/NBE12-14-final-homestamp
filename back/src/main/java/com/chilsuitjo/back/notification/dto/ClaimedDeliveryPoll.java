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
 * @param lease 가져가며 DB에 적은 {@code next_poll_at}(마이크로초까지, DB 값과 같음). 결과 없음의 저장 전 예약 시각이 달라졌는지 본다. 조회
 *     소유권은 별도 토큰으로 검증한다
 * @param token 조회 세대 식별자. 회수 후 오래된 결과를 버리는 데 사용한다
 */
public record ClaimedDeliveryPoll(
    UUID noticeId, DeliveryQuery query, boolean pastDeadline, Instant lease, UUID token) {

  public ClaimedDeliveryPoll {
    Objects.requireNonNull(noticeId, "noticeId");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(token, "token");
  }
}
