package com.chilsuitjo.back.payment.dto;

import java.util.UUID;

/**
 * 지불 기록 결과. 같은 {@code Idempotency-Key}로 다시 온 요청이면 처음 만든 지불이고 {@code replayed}가 참이다(06 4-0). 모양은
 * {@code RepairCaseCreated}와 같다.
 *
 * @param id 만든 지불
 * @param replayed 다시 온 요청인지. 응답 본문에는 넣지 않고 {@code Idempotent-Replayed} 머리로 알린다
 */
public record PaymentRecordResult(UUID id, boolean replayed) {}
