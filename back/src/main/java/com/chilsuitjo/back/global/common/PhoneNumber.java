package com.chilsuitjo.back.global.common;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;

/**
 * 전화번호 정규화(06 4-0 "전화번호"): 숫자만 남긴 형식으로 저장·비교한다({@code 010-1234-5678} → {@code 01012345678}).
 *
 * <p>국내 휴대폰 번호만 받는다: {@code 010}은 11자리, 예전 번호 {@code 011·016·017·018·019}는 10~11자리. {@code
 * 012}·{@code 013}·{@code 015} 같은 휴대폰이 아닌 {@code 01x} 번호와 유선 번호는 {@code VALIDATION_FAILED}다.
 *
 * <p>인증 번호(auth)와 초대 번호(room)를 같은 형식으로 맞춰야 비교할 수 있어서 여러 도메인이 쓰는 이곳에 둔다(#163).
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

  /**
   * 가린 번호(03 "당사자끼리 휴대폰 번호는 가린 번호만", 06 동의 화면 "010-****-5678"). 앞 세 자리와 뒤 네 자리만 남기고 가운데는 자릿수만큼
   * {@code *}로 바꾼다({@code 01012345678} → {@code 010-****-5678}, 10자리 {@code 0111234567} → {@code
   * 011-***-4567}). 정규화한 번호를 받는다. 형식이 맞지 않으면 원래 번호를 드러내지 않도록 {@code IllegalArgumentException}이다(값은
   * 문구에 넣지 않음).
   */
  public static String mask(String normalized) {
    if (normalized == null || !normalized.matches(MOBILE)) {
      throw new IllegalArgumentException("정규화한 휴대폰 번호가 아니에요");
    }
    int middle = normalized.length() - 7;
    return normalized.substring(0, 3)
        + "-"
        + "*".repeat(middle)
        + "-"
        + normalized.substring(normalized.length() - 4);
  }
}
