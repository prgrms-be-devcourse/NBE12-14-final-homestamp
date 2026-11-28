package com.chilsuitjo.back.settlement.dto;

import java.util.UUID;

/**
 * 정산 열기 결과(06 4-8).
 *
 * @param id 만든 정산
 * @param replayed 같은 키로 다시 온 요청인지(06 4-0 {@code Idempotent-Replayed})
 */
public record SettlementCreated(UUID id, boolean replayed) {}
