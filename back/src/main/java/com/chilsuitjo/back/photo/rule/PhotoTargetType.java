package com.chilsuitjo.back.photo.rule;

public enum PhotoTargetType {
  REPAIR_CASE, // 수리 건 사진
  INSPECTION_ITEM, // 점검 하자 항목 사진
  INSPECTION, // 입주·퇴거 점검의 부위 사진(하자 항목 아님)
  PAYMENT, // 지불 증빙 사진
  CAUSE_NOTE, // 원인 설명 사진
  SETTLEMENT_ITEM, // 정산 항목 증빙 사진
  PROGRESS_NOTE, // 진행 상황 기록 사진
  CORRECTION_REQUEST // 고친 내용을 다시 요청할 때 추가한 근거 사진
}
