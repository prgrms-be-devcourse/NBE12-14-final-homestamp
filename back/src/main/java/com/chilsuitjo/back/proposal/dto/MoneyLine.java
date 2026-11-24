package com.chilsuitjo.back.proposal.dto;

import com.chilsuitjo.back.proposal.rule.LineKind;

/**
 * 제안 금액 한 줄(05 2-4 {@code proposal_line}). 화면 요청({@link ProposalRequest})·응답({@link
 * ProposalItemResponse})과 서비스({@link ProposalCommand}·{@link ProposalItemResult})가 같이 쓴다.
 *
 * @param kind 부담 주체 또는 정산 금액 종류
 * @param thirdPartyLabel 제3자 표시 이름
 * @param amount 이 줄의 금액
 */
public record MoneyLine(LineKind kind, String thirdPartyLabel, long amount) {}
