package com.chilsuitjo.back.notification.rule;

/** 서비스 안 알림 종류(05 {@code in_app_notification.kind}, 03 K "서비스 안 알림"). */
public enum InAppKind {
  /** 응답 필요(응답 요청이 정함). */
  RESPONSE,
  /** 알려 줌. */
  INFO,
  /** 서비스 안내. */
  SERVICE
}
