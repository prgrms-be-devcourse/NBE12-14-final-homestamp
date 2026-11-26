package com.chilsuitjo.back.notification.rule;

/**
 * 아웃박스 줄 상태(05 {@code outbox.status}). {@code SENDING}은 업체에 보내기 직전에 먼저 커밋한다(04 4-2). 켜질 때 남은 {@code
 * SENDING}은 다시 보내지 않고 전달 결과 조회로 맞춘다(OPS-1).
 *
 * <p>{@code CANCELLED}는 보내기 전 취소한 줄이다(#100 U2). 일반 취소는 {@code PENDING}에서만 오며, 작업자가 가져간 뒤라도 실제 시도 전에
 * 초대 링크 무효를 확인한 경우 {@code SENDING}에서 올 수 있다. 다시 보내거나 다시 시도하지 않는다.
 */
public enum OutboxStatus {
  PENDING,
  SENDING,
  DONE,
  FAILED,
  CANCELLED
}
