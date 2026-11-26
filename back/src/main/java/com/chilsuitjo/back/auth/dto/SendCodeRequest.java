package com.chilsuitjo.back.auth.dto;

import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 인증 문자 보내기 요청(06 4-1 {@code POST /api/auth/phone-verifications}). 번호 모양은 {@code PhoneNumber}가
 * 정규화하며 확인한다. 번호가 로그에 남지 않게 {@code toString}에 넣지 않는다.
 *
 * @param phone 휴대폰 번호({@code 010-1234-5678}·{@code 01012345678} 모두 받음)
 * @param purpose 인증 목적(SIGNUP·RESET·FIND_ID·CHANGE)
 */
public record SendCodeRequest(@NotBlank String phone, @NotNull VerificationPurpose purpose) {

  @Override
  public String toString() {
    return "SendCodeRequest[purpose=" + purpose + "]";
  }
}
