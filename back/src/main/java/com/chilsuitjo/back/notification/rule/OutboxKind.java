package com.chilsuitjo.back.notification.rule;

/** 아웃박스 작업 종류(05 {@code outbox.kind}). 이 작업자는 {@code SMS}만 꺼낸다. */
public enum OutboxKind {
  SMS,
  S3_DELETE,
  AUDIT_WRITE,
  ORPHAN_CHECK
}
