package com.chilsuitjo.back.photo.rule;

/**
 * 사진이 붙는 대상({@code photo}의 대상 칸, 05 2-8). {@code Photo.forTarget}이 이 값으로 대상 칸 하나를 고른다. 점검의 부위
 * 사진({@code inspection_id})은 부위도 함께 받아야 해서 여기 넣지 않고 {@code Photo.forInspection}으로 만든다.
 *
 * <p>행동·응답 요청의 대상 종류 {@code global.common.TargetKind}와 이름이 겹치지만, 사진이 붙을 수 있는 대상만 남기려고 따로 둔다.
 */
public enum PhotoTargetType {
  /** 수리 건 사진. */
  REPAIR_CASE,
  /** 점검 하자 항목 사진. */
  INSPECTION_ITEM,
  /** 지불 증빙 사진. */
  PAYMENT,
  /** 원인 설명 사진. */
  CAUSE_NOTE,
  /** 정산 항목 증빙 사진. */
  SETTLEMENT_ITEM,
  /** 진행 상황 사진. */
  PROGRESS_NOTE,
  /** 고친 내용을 다시 요청할 때 더한 근거 사진. */
  CORRECTION_REQUEST
}
