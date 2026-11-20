package com.chilsuitjo.back.notification.rule;

/**
 * 혼자 진행할 수 있는 이유(03 1-4 "혼자 진행 가능"의 ①~⑥). 값은 05 ERD {@code response_request.solo_reason}(V1 CHECK)와
 * 같다.
 *
 * <p>⑥은 연결 전({@link #NOT_CONNECTED})과 상대 없음({@link #NO_COUNTERPART}) 둘로 나뉜다.
 */
public enum SoloReason {
  UNCONFIRMED,
  OPTED_OUT,
  COUNTERPART_WITHDRAWN,
  SHARING_STOPPED,
  SMS_UNDELIVERED,
  NOT_CONNECTED,
  NO_COUNTERPART
}
