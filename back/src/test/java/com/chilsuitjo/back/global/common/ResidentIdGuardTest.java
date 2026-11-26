package com.chilsuitjo.back.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResidentIdGuardTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "900101-1234567",
        "9001011234567",
        "900101 1234567",
        "제 번호는 001231-4234567 이에요",
        "991231-8123456",
        "글 앞에900101-1234567"
      })
  void 주민번호_형태면_찾는다(String text) {
    assertThat(ResidentIdGuard.contains(text)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "010-1234-5678",
        "901301-1234567", // 13월
        "900100-1234567", // 0일
        "900132-1234567", // 32일
        "900101-9234567", // 뒤 첫 자리 9
        "900101-0234567", // 뒤 첫 자리 0
        "900101-123456", // 뒤 6자리
        "1900101-1234567", // 앞에 숫자가 더 붙음
        "900101-12345678", // 뒤에 숫자가 더 붙음
        "900101--1234567", // 구분이 둘
        "천장 누수, 2026-11-23에 봤어요",
        ""
      })
  void 주민번호_형태가_아니면_통과한다(String text) {
    assertThat(ResidentIdGuard.contains(text)).isFalse();
  }

  @Test
  void null은_통과한다() {
    assertThat(ResidentIdGuard.contains(null)).isFalse();
    assertThatCode(() -> ResidentIdGuard.check("누수", null)).doesNotThrowAnyException();
  }

  @Test
  void 여러_칸_중_하나라도_있으면_RESIDENT_ID_BLOCKED() {
    assertThatThrownBy(() -> ResidentIdGuard.check("누수", null, "900101-1234567"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESIDENT_ID_BLOCKED));
  }
}
