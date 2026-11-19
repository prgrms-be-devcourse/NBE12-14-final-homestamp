package com.chilsuitjo.back.notification.rule;

/** 응답 요청 종류. 값은 05 ERD {@code response_request.kind}(V1 CHECK)와 같다. */
public enum RequestKind {
  DEFECT_CONFIRM,
  SITUATION_CHECK,
  CORRECTION_CONFIRM,
  AMOUNT_CONFIRM,
  PROPOSAL,
  CLOSE_CONFIRM,
  INSPECTION_CONFIRM,
  RECEIPT_CONFIRM,
  SETTLE_FINISH,
  END_REQUEST,
  PROGRESS_CHECK
}
