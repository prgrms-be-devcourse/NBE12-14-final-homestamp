package com.chilsuitjo.back.global.common;

/**
 * 링크가 무효가 된 이유. 값은 05 ERD {@code link.invalidated_reason}(V1 CHECK, V15에서 {@code INVITE_REJECTED}
 * 더함)와 같다(03 C).
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
  SETTLED,
  /**
   * 받는 자리가 초대를 거절함({@code REJECT_INVITE}, "제 집이 아니에요"). room이 거절 저장에서 그 자리 단위 {@code
   * invalidateInvites}로 넘긴다(#392). 다시 초대하면 새 링크가 생기고, 이 링크는 다시 열리지 않는다(05 불변식 7).
   */
  INVITE_REJECTED
}
