package com.chilsuitjo.back.settlement.dto;

/**
 * 정산 끝내기 확인(06 4-8 {@code finish/confirm}).
 *
 * @param seenVersionNo 확인할 때 본 정산 모습 번호(상세의 {@code snapshotNo}). 지금과 다르면 {@code
 *     VERSION_CONFLICT}(06 SET-5e)
 */
public record FinishConfirmRequest(Integer seenVersionNo) {}
