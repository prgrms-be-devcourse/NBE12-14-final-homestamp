package com.chilsuitjo.back.proposal.dto;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.proposal.rule.ProposalOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 서비스에서 돌려주는 개별 제안. 역제안·교체 관계를 포함한다.
 *
 * @param id 제안 ID
 * @param proposedBy 제안한 당사자 측
 * @param round 제안 회차
 * @param counterOfId 역제안의 대상이 된 제안 ID
 * @param replacesId 상대 응답 전에 교체한 이전 제안 ID
 * @param lines 제안 금액 줄
 * @param noCost 수리비 비용 없음 제안인지
 * @param exclude 정산 항목 빼기 제안인지
 * @param reason 정산 항목 [바꾸기]·[빼기]의 사유(05 {@code proposal.reason}, 06 SET-2). 수리비 부담·보증금은 {@code
 *     null}
 * @param total 제안 총액
 * @param createdAt 제안 생성 시각
 * @param outcome 제안 처리 상태
 * @param closedReason {@code outcome}이 {@code CLOSED}일 때 닫힌 이유. 그 밖에는 {@code null}
 */
public record ProposalItemResult(
    UUID id,
    PartySide proposedBy,
    int round,
    UUID counterOfId,
    UUID replacesId,
    List<MoneyLine> lines,
    boolean noCost,
    boolean exclude,
    String reason,
    long total,
    Instant createdAt,
    ProposalOutcome outcome,
    ClosedReason closedReason) {}
