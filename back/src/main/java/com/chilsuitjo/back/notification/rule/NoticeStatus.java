package com.chilsuitjo.back.notification.rule;

/**
 * 통지 상태(05 {@code notice.status}, 03 K). 앞으로만 간다. 아웃박스의 {@code SENDING}은 여기에 두지 않는다(화면은 "발송 요청됨").
 *
 * <p>{@code CANCELLED}는 보내기 전에 일부러 취소한 통지다(#100 U2, 번호를 고쳐 다시 초대). 업체에 닿지 못한 {@code FAILED}와 달리 다시
 * 보내기·전달 결과 조회·"문자가 가지 않음"의 대상이 아니다. {@code REQUESTED}에서만 온다.
 */
public enum NoticeStatus {
  REQUESTED,
  SENT,
  DELIVERED,
  VIEWED,
  FAILED,
  CANCELLED
}
