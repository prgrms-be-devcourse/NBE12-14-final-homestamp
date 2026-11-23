package com.chilsuitjo.back.repair.dto;

import java.util.UUID;

/**
 * 하자 등록 결과. 같은 {@code Idempotency-Key}로 다시 온 요청이면 처음 만든 수리 건이고 {@code replayed}가 참이다(06 4-0).
 *
 * @param id 만든 수리 건
 * @param replayed 다시 온 요청인지. 응답 본문에는 넣지 않고 {@code Idempotent-Replayed} 머리로 알린다
 */
public record RepairCaseCreated(UUID id, boolean replayed) {}
