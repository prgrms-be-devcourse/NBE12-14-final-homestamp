package com.chilsuitjo.back.auth.rule;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;

/**
 * 전화번호 정규화(06 4-0 "전화번호"): 숫자만 남긴 형식으로 저장·비교한다({@code 010-1234-5678} → {@code 01012345678}).
 *
 * <p>국내 휴대폰 번호만 받는다: {@code 010}은 11자리, 예전 번호 {@code 011·016·017·018·019}는 10~11자리. {@code
 * 012}·{@code 013}·{@code 015} 같은 휴대폰이 아닌 {@code 01x} 번호와 유선 번호는 {@code VALIDATION_FAILED}다.
 */
public final class PhoneNumber {

  private static final String MOBILE = "010\\d{8}|01[16789]\\d{7,8}";

  private PhoneNumber() {}

  public static String normalize(String input) {
    if (input == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    String digits = input.replaceAll("[^0-9]", "");
    if (!digits.matches(MOBILE)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return digits;
  }
}
