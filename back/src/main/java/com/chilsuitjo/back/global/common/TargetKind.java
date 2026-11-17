package com.chilsuitjo.back.global.common;

/**
 * 기록 대상 종류. 행동의 대상(05 {@code action}의 대상 칸)과 응답 요청의 대상이 같이 쓴다(decisions 2026-11-17).
 *
 * <p>{@link #ROOM_RECORD}는 대상 칸이 없는 행동(호실 기록 자체: 이사함, 정산 끝내기, 호실 만들기)을 가리킬 때만 쓴다. 행동 기록에는 대상 칸 없이
 * 남는다.
 */
public enum TargetKind {
  ROOM_RECORD,
  PARTY,
  LINK,
  ROOM_PART,
  NOTICE,
  REPAIR_CASE,
  PAYMENT,
  INSPECTION,
  INSPECTION_ITEM,
  SETTLEMENT,
  SETTLEMENT_ITEM,
  SETTLEMENT_TRANSFER,
  PROPOSAL,
  RESPONSE_REQUEST,
  CORRECTION_REQUEST,
  CAUSE_NOTE,
  PROGRESS_NOTE,
  PHOTO
}
