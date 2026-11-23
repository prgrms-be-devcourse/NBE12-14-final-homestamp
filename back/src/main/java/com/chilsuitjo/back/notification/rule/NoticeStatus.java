package com.chilsuitjo.back.notification.rule;

/**
 * 통지 상태(05 {@code notice.status}, 03 K). 앞으로만 간다. 아웃박스의 {@code SENDING}은 여기에 두지 않는다(화면은 "발송 요청됨").
 */
public enum NoticeStatus {
  REQUESTED,
  SENT,
  DELIVERED,
  VIEWED,
  FAILED
}
