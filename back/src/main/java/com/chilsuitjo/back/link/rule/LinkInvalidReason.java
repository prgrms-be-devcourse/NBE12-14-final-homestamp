package com.chilsuitjo.back.link.rule;

/** 링크가 무효가 된 이유. 값은 05 ERD {@code link.invalidated_reason}(V1 CHECK)와 같다(03 C). */
public enum LinkInvalidReason {
  REINVITED,
  CONNECTED,
  RESIDENCE_ENDED,
  ISSUER_WITHDRAWN,
  SHARING_STOPPED,
  SETTLED
}
