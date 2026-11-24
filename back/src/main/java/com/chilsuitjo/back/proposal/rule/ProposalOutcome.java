package com.chilsuitjo.back.proposal.rule;

/**
 * 개별 제안의 처리 상태. {@code proposal}은 B라 상태 칸이 없어서(05 2-4), 이 값은 그 안건의 {@code PROPOSAL} 응답 요청 상태와 제안
 * 관계({@code counter_of_id}·{@code replaces_id})에서 읽어 만든다.
 */
public enum ProposalOutcome {
  /** 응답 대기. */
  WAITING,
  /** 미확인. */
  UNCONFIRMED,
  /** 합의됨. */
  AGREED,
  /** 이견이 남겨짐. */
  DISPUTED,
  /** 역제안이 남겨짐. */
  COUNTERED,
  /** 철회됨. */
  WITHDRAWN,
  /** 다른 안으로 교체됨. */
  REPLACED,
  /**
   * 답 없이 닫힘. 이유는 {@code closedReason}({@code ClosedReason}, 05 {@code
   * response_request.closed_reason}).
   */
  CLOSED
}
