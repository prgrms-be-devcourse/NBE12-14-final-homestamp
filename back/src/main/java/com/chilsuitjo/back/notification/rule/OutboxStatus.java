package com.chilsuitjo.back.notification.rule;

/**
 * 아웃박스 줄 상태(05 {@code outbox.status}). {@code SENDING}은 업체에 보내기 직전에 먼저 커밋한다(04 4-2). 켜질 때 남은 {@code
 * SENDING}은 다시 보내지 않고 전달 결과 조회로 맞춘다(OPS-1).
 */
public enum OutboxStatus {
  PENDING,
  SENDING,
  DONE,
  FAILED
}
