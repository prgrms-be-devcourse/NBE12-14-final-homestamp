package com.chilsuitjo.back.proposal.rule;

/**
 * 개별 제안의 처리 결과와 구분되는 안건 전체의 현재 상태. 세 종류의 안건이 같이 쓴다.
 *
 * <p>DB에는 head domain의 status 칸에 저장된다. 값이 domain마다 조금씩 달라서 {@code AgendaHeadStore} 구현이 아래 표대로 바꾼다
 * (decisions 2026-11-24). 표에 없는 칸은 그 안건에 없는 상태다.
 *
 * <pre>
 * AgendaStatus | MoneyStatus   | SettlementItemStatus | DepositStatus
 *              | (repair_case) | (settlement_item)    | (settlement)
 * -------------+---------------+----------------------+--------------
 * NONE         | UNDECIDED     | -                    | NOT_ENTERED
 * PENDING      | PENDING       | PENDING              | PENDING
 * AGREED       | AGREED        | AGREED               | AGREED
 * NO_COST      | NO_COST       | -                    | -
 * DISPUTE      | DISPUTE       | DISPUTE              | DISPUTE
 * EXCLUDED     | -             | EXCLUDED             | -
 * </pre>
 */
public enum AgendaStatus {
  /** 안 없음. */
  NONE,
  /** 응답 대기. */
  PENDING,
  /** 합의. */
  AGREED,
  /** 비용 없음. 수리비 부담만. */
  NO_COST,
  /** 이견. */
  DISPUTE,
  /** 정산 항목에서 빠짐. 정산 항목만. */
  EXCLUDED
}
