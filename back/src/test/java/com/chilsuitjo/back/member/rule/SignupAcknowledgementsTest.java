package com.chilsuitjo.back.member.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 가입 확인 항목(06 ACC-1a·1b, CONS-1a, 4-10 {@code AGE_CONFIRM_REQUIRED}·{@code TERMS_REQUIRED}). */
class SignupAcknowledgementsTest {

  @Test
  void ACC_1a_만_14세_확인이_없으면_AGE_CONFIRM_REQUIRED() {
    assertError(
        () -> SignupAcknowledgements.confirm(false, "v1", true, "v1", true, "v1"),
        ErrorCode.AGE_CONFIRM_REQUIRED);
    assertError(
        () -> SignupAcknowledgements.confirm(null, "v1", true, "v1", true, "v1"),
        ErrorCode.AGE_CONFIRM_REQUIRED);
  }

  @Test
  void 만_14세를_먼저_본다_다른_항목도_빠졌어도_AGE_CONFIRM_REQUIRED() {
    assertError(
        () -> SignupAcknowledgements.confirm(null, null, null, null, null, null),
        ErrorCode.AGE_CONFIRM_REQUIRED);
  }

  @Test
  void 약관을_체크하지_않으면_TERMS_REQUIRED() {
    assertError(
        () -> SignupAcknowledgements.confirm(true, "v1", false, "v1", true, "v1"),
        ErrorCode.TERMS_REQUIRED);
  }

  @Test
  void 처리_안내를_확인하지_않으면_TERMS_REQUIRED() {
    assertError(
        () -> SignupAcknowledgements.confirm(true, "v1", true, "v1", null, "v1"),
        ErrorCode.TERMS_REQUIRED);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "123456789012345678901"})
  void 체크한_항목의_문구_판이_비었거나_칸보다_길면_입력_오류다(String version) {
    assertError(
        () -> SignupAcknowledgements.confirm(true, version, true, "v1", true, "v1"),
        ErrorCode.VALIDATION_FAILED);
    assertError(
        () -> SignupAcknowledgements.confirm(true, "v1", true, version, true, "v1"),
        ErrorCode.VALIDATION_FAILED);
    assertError(
        () -> SignupAcknowledgements.confirm(true, "v1", true, "v1", true, version),
        ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void ACC_1b_CONS_1a_세_항목과_각_문구_판을_돌려준다_처리방침_동의는_없다() {
    SignupAcknowledgements confirmed =
        SignupAcknowledgements.confirm(
            true, "2026-11-12", true, "2026-11-13", true, "12345678901234567890");

    assertThat(confirmed.textVersions())
        .containsOnlyKeys(
            AcknowledgementKind.AGE_14_CONFIRMED,
            AcknowledgementKind.TERMS_AGREED,
            AcknowledgementKind.PRIVACY_NOTICE_ACK)
        .containsEntry(AcknowledgementKind.AGE_14_CONFIRMED, "2026-11-12")
        .containsEntry(AcknowledgementKind.TERMS_AGREED, "2026-11-13")
        .containsEntry(AcknowledgementKind.PRIVACY_NOTICE_ACK, "12345678901234567890");
  }

  private static void assertError(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }
}
