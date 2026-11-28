package com.chilsuitjo.back.notification.rule;

/**
 * 혼자 진행할 수 있는 이유(03 1-4 "혼자 진행 가능"의 ①~⑥). 값은 05 ERD {@code response_request.solo_reason}(V1 CHECK)와
 * 같다.
 *
 * <p>⑥은 연결 전({@link #NOT_CONNECTED})과 상대 없음({@link #NO_COUNTERPART}) 둘로 나뉜다. {@link
 * #INVITE_REJECTED}는 초대한 번호의 상대가 "제 집이 아니에요"로 거절해 그 상대에게 확인받을 수 없음이다(#421 조건 1, V16). 세입자 없음({@link
 * #NO_COUNTERPART})·아직 답할 수 있는 상대({@link #NOT_CONNECTED})와 뜻이 달라 따로 둔다.
 */
public enum SoloReason {
  UNCONFIRMED,
  OPTED_OUT,
  COUNTERPART_WITHDRAWN,
  SHARING_STOPPED,
  SMS_UNDELIVERED,
  NOT_CONNECTED,
  NO_COUNTERPART,
  INVITE_REJECTED
}
