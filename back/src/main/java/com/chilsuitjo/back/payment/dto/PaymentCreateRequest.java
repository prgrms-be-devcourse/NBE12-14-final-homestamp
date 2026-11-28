package com.chilsuitjo.back.payment.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 지불 기록 요청(MONEY-1, 06 {@code POST /api/repair-cases/{id}/payments}).
 *
 * <p>낸 사람·받는 사람의 종류, 금액, 날짜는 필수이고 {@code amount >= 0}(05 {@code payment_version})이다. 관계 설명({@code
 * label})은 제3자일 때만 1~30자로 받는다(MONEY-1b, 제안의 제3자 줄과 같음). 어기면 {@code VALIDATION_FAILED}, 글에 주민등록번호
 * 형태가 들면 {@code RESIDENT_ID_BLOCKED}다. 금액이 빠졌는지 가리려고 {@code Long}이고, 빠진 칸을 거절하지 않게 {@code
 * similarConfirmed}는 {@code Boolean}이다(Jackson 3은 기본형 칸에 null을 넣지 않음).
 *
 * @param payer 낸 사람(종류와, 제3자면 관계)
 * @param payee 받는 사람(종류와, 제3자면 관계)
 * @param amount 금액(원)
 * @param paidOn 사용자가 적은 낸 날짜
 * @param evidenceKind 증빙 종류(선택, 30자까지)
 * @param uploadTickets 증빙 사진 업로드 표(선택, 10장까지)
 * @param similarConfirmed 30일 안 비슷한 지불이 있다는 안내를 보고도 남기는지(MONEY-1c). 빠지면 false
 */
public record PaymentCreateRequest(
    PayerRequest payer,
    PayeeRequest payee,
    Long amount,
    LocalDate paidOn,
    String evidenceKind,
    List<String> uploadTickets,
    Boolean similarConfirmed) {

  /** 비슷한 기록 안내를 보고도 남기는지. 칸이 없으면 아니다(제안의 {@code noCost}와 같음). */
  public boolean isSimilarConfirmed() {
    return Boolean.TRUE.equals(similarConfirmed);
  }
}
