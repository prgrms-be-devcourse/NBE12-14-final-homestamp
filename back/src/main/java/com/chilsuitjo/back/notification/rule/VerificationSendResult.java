package com.chilsuitjo.back.notification.rule;

/** 인증 문자 바로 보내기 결과(04 4-2 "인증 문자는 아웃박스를 쓰지 않는다", 06 ACC-1j). */
public enum VerificationSendResult {
  /** 문자 업체가 접수했다. */
  SENT,
  /** 문자 업체가 실패로 답했다. auth는 인증 행을 지우고 {@code SMS_SEND_FAILED}로 답한다(하루 횟수에 세지 않음). */
  FAILED,
  /** 시간 제한(5초) 안에 답이 없었다. 실제로 갔을 수 있어 보낸 것으로 보고 횟수에 센다. */
  UNKNOWN
}
