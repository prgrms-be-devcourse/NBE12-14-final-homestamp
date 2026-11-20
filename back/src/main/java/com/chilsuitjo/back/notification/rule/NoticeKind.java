package com.chilsuitjo.back.notification.rule;

/** 통지 종류(05 {@code notice.kind}, V1 CHECK와 같다). */
public enum NoticeKind {
  INVITE,
  REQUEST,
  RENOTIFY,
  SETTLEMENT_INVITE,
  INFO
}
