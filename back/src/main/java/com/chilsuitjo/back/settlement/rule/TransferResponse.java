package com.chilsuitjo.back.settlement.rule;

/** 송금 기록을 남기지 않은 측의 응답(05 2-7). 송금의 응답 전 상태는 NONE이다. */
public enum TransferResponse {
  NONE,
  ACK,
  NEED_MORE,
  DISPUTE
}
