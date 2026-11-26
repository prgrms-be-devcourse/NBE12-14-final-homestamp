package com.chilsuitjo.back.proposal.dto;

import java.util.UUID;

/**
 * 제안 처리 결과. 멱등 재요청 여부를 함께 전달한다.
 *
 * @param proposalId 처리한 제안 ID
 * @param agenda 처리 후 안건
 * @param replayed 같은 멱등 키의 기존 처리 결과를 돌려준 것인지
 */
public record ProposalResult(UUID proposalId, AgendaResult agenda, boolean replayed) {}
