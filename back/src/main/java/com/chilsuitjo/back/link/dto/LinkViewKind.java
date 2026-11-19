package com.chilsuitjo.back.link.dto;

/** 링크 화면 종류(06 4-4 {@code links/view}). */
public enum LinkViewKind {
  /** 기록 하나(수리 건 또는 점검)를 공유한 초대 링크. */
  RECORD,
  /** 기록 없이 호실만 초대한 링크. 안내만 보인다(기록 내용 없음). */
  ROOM_INVITE,
  /** 정산 초대 링크(LINK-3). 안내만 보이고 금액·항목은 없다. */
  SETTLEMENT_INVITE
}
