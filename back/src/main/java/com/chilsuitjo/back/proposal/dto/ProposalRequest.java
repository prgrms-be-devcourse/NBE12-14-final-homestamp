package com.chilsuitjo.back.proposal.dto;

import java.util.List;

/**
 * 화면에서 보내는 금액 제안 요청. 정산 화면에서도 같은 형식을 쓴다.
 *
 * @param lines 제안 금액 줄
 * @param noCost 수리비 비용 없음 제안인지. 빠지면 {@code false}다. 원시 타입이면 Jackson 3 기본값({@code
 *     FAIL_ON_NULL_FOR_PRIMITIVES})이 칸이 없는 요청을 읽지 못해 400이 되어 감싼 타입으로 둔다
 * @param reason 정산 항목 [바꾸기]·[빼기]의 사유(05 {@code proposal.reason}, 06 SET-2). 수리비 부담·보증금은 {@code
 *     null}. 이견·역제안 사유는 이 칸에 담지 않는다(행동 기록의 {@code reason}, decisions 2026-11-19)
 */
public record ProposalRequest(List<MoneyLine> lines, Boolean noCost, String reason) {

  /** 비용 없음 제안인지. 칸이 없으면 아니다. */
  public boolean isNoCost() {
    return Boolean.TRUE.equals(noCost);
  }
}
