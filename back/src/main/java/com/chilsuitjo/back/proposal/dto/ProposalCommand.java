package com.chilsuitjo.back.proposal.dto;

import java.util.List;

/**
 * 제안 서비스에 전달하는 금액 안과 사유.
 *
 * @param lines 제안하는 금액 줄
 * @param noCost 수리비를 비용 없음으로 제안하는지
 * @param exclude 정산 항목을 빼기로 제안하는지
 * @param reason 정산 항목 [바꾸기]·[빼기]의 사유(05 {@code proposal.reason}, 06 SET-2). 수리비 부담·보증금은 {@code
 *     null}. 이견·역제안 사유는 이 칸에 담지 않는다(행동 기록의 {@code reason}, decisions 2026-11-19)
 */
public record ProposalCommand(
    List<MoneyLine> lines, boolean noCost, boolean exclude, String reason) {}
