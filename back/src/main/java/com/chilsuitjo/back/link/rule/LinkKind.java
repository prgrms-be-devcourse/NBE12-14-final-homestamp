package com.chilsuitjo.back.link.rule;

/** 링크 종류. 값은 05 ERD {@code link.kind}(V1 CHECK)와 같다. */
public enum LinkKind {
  /** 거주 중 초대(기록 하나 또는 호실 초대 화면). */
  INVITE,
  /** 거주 종료 뒤 연결되지 않은 상대에게 함께 정산하자고 권하는 링크(LINK-3, Should). */
  SETTLEMENT_INVITE
}
