package com.chilsuitjo.back.repair.rule;

/**
 * 수리비 부담 합의 상태(03 E, 05 {@code repair_case.money_status}). 값은 V1 CHECK와 같다. 돈 규칙은 C의 {@code
 * ProposalTransitions}에 있고 B는 이 칸만 옮긴다.
 */
public enum MoneyStatus {
  UNDECIDED,
  PENDING,
  AGREED,
  DISPUTE,
  NO_COST
}
