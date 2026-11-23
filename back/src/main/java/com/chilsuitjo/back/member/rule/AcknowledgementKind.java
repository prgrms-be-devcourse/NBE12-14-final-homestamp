package com.chilsuitjo.back.member.rule;

/**
 * 가입할 때 남기는 확인 기록 세 가지(05 2-1 {@code member_acknowledgement.kind}, 06 ACC-1b·CONS-1a). 처리방침 "동의"는
 * 없다. 기록 공유 동의는 여기가 아니라 행동 기록 {@code SHARE_CONSENT}다.
 */
public enum AcknowledgementKind {
  /** 만 14세 이상이에요. */
  AGE_14_CONFIRMED,
  /** 이용약관에 동의해요. */
  TERMS_AGREED,
  /** 개인정보 처리 안내를 확인했어요. */
  PRIVACY_NOTICE_ACK
}
