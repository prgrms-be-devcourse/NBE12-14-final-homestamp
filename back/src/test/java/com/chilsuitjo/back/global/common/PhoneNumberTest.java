package com.chilsuitjo.back.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 06 4-0 "전화번호": 숫자만 남긴 형식으로 저장·비교한다. */
class PhoneNumberTest {

  @ParameterizedTest
  @ValueSource(strings = {"010-1234-5678", "01012345678", " 010 1234 5678 ", "010.1234.5678"})
  void 숫자만_남긴다(String input) {
    assertThat(PhoneNumber.normalize(input)).isEqualTo("01012345678");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "011-123-4567",
        "0111234567",
        "01612345678",
        "017-1234-5678",
        "0181234567",
        "019-123-4567"
      })
  void 예전_휴대폰_번호도_받는다(String input) {
    assertThat(PhoneNumber.normalize(input)).isEqualTo(input.replaceAll("[^0-9]", ""));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "abc",
        "02-123-4567",
        "010-123",
        "010-123-4567", // 010은 11자리만
        "0101234567890",
        "012-1234-5678",
        "013-1234-5678",
        "014-1234-5678",
        "015-1234-5678",
        "01112345",
        "011123456789"
      })
  void 국내_휴대폰_번호_모양이_아니면_VALIDATION_FAILED(String input) {
    assertThatThrownBy(() -> PhoneNumber.normalize(input))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }
}
