package com.chilsuitjo.back.room.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 06 CONS-2b 공유 동의 문구 판. 가입 확인 항목 판과 같은 방식(decisions 2026-11-25). */
class ConsentVersionTest {

  @Test
  void 비지_않은_20자_이하_판은_그대로_돌려준다() {
    assertThat(ConsentVersion.require("v1")).isEqualTo("v1");
    assertThat(ConsentVersion.require("a".repeat(20))).hasSize(20);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void CONS_2b_판이_없으면_CONSENT_REQUIRED다(String version) {
    expect(ErrorCode.CONSENT_REQUIRED, version);
  }

  @Test
  void 판이_20자를_넘으면_VALIDATION_FAILED다() {
    expect(ErrorCode.VALIDATION_FAILED, "a".repeat(21));
  }

  private static void expect(ErrorCode code, String version) {
    assertThatThrownBy(() -> ConsentVersion.require(version))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
