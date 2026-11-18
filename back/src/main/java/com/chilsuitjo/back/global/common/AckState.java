package com.chilsuitjo.back.global.common;

/** 응답의 지금 상태. 수리 건 하자 인정과 점검 항목 상태가 같은 값을 쓴다(05 ERD). */
public enum AckState {
  /** 아직 아무도 고르지 않음. */
  PENDING,
  ACK,
  NEED_MORE,
  DISPUTE,
  /** 서버가 정하는 "확인 없음". */
  UNCONFIRMED
}
