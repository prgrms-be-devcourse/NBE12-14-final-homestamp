package com.chilsuitjo.back.payment.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 새 지불과 같은 수리 건·방향·금액인 기존 지불 하나(06 MONEY-1a·1c, 05 2-5 "중복 검사는 서비스"). 방향은 낸 사람·받는 사람의 종류 쌍이다. 관계
 * 설명({@code label})은 자유 글이라 비교하지 않는다. 값은 지금 판 기준이다.
 *
 * <p>1a(상대의 확인 전 기록)인지 1c(30일 안 비슷한 기록)인지는 {@code PaymentTransitions}가 이 값으로 가린다.
 *
 * @param paymentId 기존 지불. {@code DUPLICATE_PAYMENT}에 {@code duplicateOf}로 싣는다
 * @param recordedBy 그 지불을 기록한 측
 * @param response 상대의 지금 응답. ACK가 아니면 확인 전이다
 * @param paidOn 사용자가 적은 낸 날짜. 30일은 이 날짜로 센다
 * @param createdAt 기록 시각. 공유를 그만둔 뒤 남겨 보이지 않는 기록을 가릴 때 쓴다
 */
public record SimilarPayment(
    UUID paymentId,
    PartySide recordedBy,
    PaymentResponse response,
    LocalDate paidOn,
    Instant createdAt) {}
