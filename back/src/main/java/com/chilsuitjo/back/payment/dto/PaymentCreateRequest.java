package com.chilsuitjo.back.payment.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 지불 기록 요청(MONEY-1, 06 {@code POST /api/repair-cases/{id}/payments}).
 *
 * <p>지금은 모양만 정했다. 칸 검증은 진짜 구현에서 넣는다. 낸 사람·받는 사람·날짜는 필수이고 {@code amount >= 0}(05 {@code
 * payment_version})이며, 어기면 {@code VALIDATION_FAILED}다. {@code label}에 주민등록번호 형태가 들면 {@code
 * RESIDENT_ID_BLOCKED}다. {@code amount}는 원시형이라 빠지면 0으로 읽히므로, 금액이 빠졌는지 가리려면 진짜 구현에서 {@code Long}으로
 * 바꾼다. 가짜({@code FakePaymentService})는 이 칸들을 검사하지 않는다.
 *
 * @param payer 낸 사람(종류와, 제3자면 관계)
 * @param payee 받는 사람(종류와, 제3자면 관계)
 * @param amount 금액(원)
 * @param paidOn 사용자가 적은 낸 날짜
 * @param evidenceKind 증빙 종류
 * @param uploadTickets 증빙 사진 업로드 표
 * @param similarConfirmed 30일 안 비슷한 지불이 있다는 안내를 보고도 남기는지(MONEY-1c)
 */
public record PaymentCreateRequest(
    PayerRequest payer,
    PayeeRequest payee,
    long amount,
    LocalDate paidOn,
    String evidenceKind,
    List<String> uploadTickets,
    boolean similarConfirmed) {}
