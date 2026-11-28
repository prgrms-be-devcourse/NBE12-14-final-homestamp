package com.chilsuitjo.back.settlement.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 정산 끝내기 행동의 결과(06 4-8 {@code finish}·{@code confirm}·{@code not-yet}·{@code alone}). 화면은 이 값을 받은 뒤
 * 정산을 다시 불러온다.
 *
 * @param id 정산
 * @param settled 정산 완료가 됐는지(확인·혼자 끝내기)
 * @param finishRequestedBy 지금 열린 끝내기 요청을 보낸 측(제안 뒤). 없으면 null
 * @param replayed 같은 키로 다시 온 요청인지
 */
public record FinishResult(
    UUID id, boolean settled, PartySide finishRequestedBy, boolean replayed) {}
