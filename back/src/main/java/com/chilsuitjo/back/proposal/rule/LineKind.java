package com.chilsuitjo.back.proposal.rule;

/**
 * 제안 줄의 종류(05 2-4 {@code proposal_line.line_kind}). 값은 V1 CHECK와 같다.
 *
 * <p>수리 건 부담은 {@code TENANT}·{@code LANDLORD}·{@code THIRD_PARTY}, 정산 항목 금액은 {@code DEDUCT}·{@code
 * ADD}, 보증금은 {@code DEPOSIT}을 쓴다.
 */
public enum LineKind {
  TENANT,
  LANDLORD,
  THIRD_PARTY,
  DEDUCT,
  ADD,
  DEPOSIT
}
