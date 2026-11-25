package com.chilsuitjo.back.notification.rule;

/**
 * 아웃박스 줄 상태(05 {@code outbox.status}). {@code SENDING}은 업체에 보내기 직전에 먼저 커밋한다(04 4-2). 켜질 때 남은 {@code
 * SENDING}은 다시 보내지 않고 전달 결과 조회로 맞춘다(OPS-1).
 *
 * <p>{@code CANCELLED}는 작업자가 가져가기 전에 취소한 줄이다(#100 U2). {@code PENDING}에서만 오고, 작업자는 {@code PENDING}만
 * 가져가므로 다시 보내거나 다시 시도하지 않는다.
 */
public enum OutboxStatus {
  PENDING,
  SENDING,
  DONE,
  FAILED,
  CANCELLED
}
