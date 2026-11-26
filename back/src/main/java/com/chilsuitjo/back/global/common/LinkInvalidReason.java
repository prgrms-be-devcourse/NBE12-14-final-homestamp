package com.chilsuitjo.back.global.common;

/**
 * 링크가 무효가 된 이유. 값은 05 ERD {@code link.invalidated_reason}(V1 CHECK)와 같다(03 C).
 *
 * <p>{@code link}가 두고 다른 도메인이 {@code InviteLinks}(notification 포트)로 넘기므로 {@code global/common}에
 * 둔다({@code ClosedReason}과 같은 자리, #163).
 */
public enum LinkInvalidReason {
  REINVITED,
  CONNECTED,
  RESIDENCE_ENDED,
  ISSUER_WITHDRAWN,
  SHARING_STOPPED,
  SETTLED
}
