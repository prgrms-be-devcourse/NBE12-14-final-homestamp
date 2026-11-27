package com.chilsuitjo.back.settlement.rule;

/** 송금 기록을 남기지 않은 측의 응답(05 2-7). 송금의 응답 전 상태는 PENDING이다. */
public enum TransferResponse {
  PENDING,
  ACK,
  NEED_MORE,
  DISPUTE
}
