package com.chilsuitjo.back.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 번호 HMAC 포트의 auth 구현(#163 1-3, decisions 2026-11-26 "번호 HMAC"). */
class PhoneHmacServiceTest {

  private final PhoneHmacs hmacs =
      new PhoneHmacService(new VerificationKeys("code-key", "ticket-key", "phone-key"));

  @Test
  void 정규화한_번호를_번호_HMAC_키로_HMAC_SHA256한_소문자_hex_64자다() throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("phone-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected =
        HexFormat.of().formatHex(mac.doFinal("01012345678".getBytes(StandardCharsets.UTF_8)));

    assertThat(hmacs.phoneHmac("01012345678"))
        .isEqualTo(expected)
        .hasSize(64)
        .matches("[0-9a-f]{64}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"010-1234-5678", " 010 1234 5678 ", "010.1234.5678"})
  void 형식만_다른_번호는_같은_값이다(String input) {
    assertThat(hmacs.phoneHmac(input)).isEqualTo(hmacs.phoneHmac("01012345678"));
  }

  @Test
  void 번호가_다르면_값이_다르다() {
    assertThat(hmacs.phoneHmac("01012345678")).isNotEqualTo(hmacs.phoneHmac("01012345679"));
  }

  @Test
  void 키가_다르면_값이_다르다() {
    PhoneHmacs other =
        new PhoneHmacService(new VerificationKeys("code-key", "ticket-key", "other-phone-key"));

    assertThat(other.phoneHmac("01012345678")).isNotEqualTo(hmacs.phoneHmac("01012345678"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "abc", "02-123-4567", "0151234567", "010123456789"})
  void 정규화할_수_없는_번호는_VALIDATION_FAILED이고_번호를_문구에_남기지_않는다(String input) {
    assertThatThrownBy(() -> hmacs.phoneHmac(input))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
        .hasMessage(ErrorCode.VALIDATION_FAILED.name());
  }

  @Test
  void 번호_잠금_키는_같은_HMAC의_앞_8바이트다() {
    VerificationKeys keys = new VerificationKeys("code-key", "ticket-key", "phone-key");
    String hmac = new PhoneHmacService(keys).phoneHmac("010-1234-5678");

    assertThat(Long.parseUnsignedLong(hmac.substring(0, 16), 16))
        .isEqualTo(keys.phoneLockKey("01012345678"));
  }

  @Test
  void 키가_있으면_포트_빈으로_등록된다() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "app.keys.verify-code=a", "app.keys.verify-ticket=b", "app.keys.phone-hmac=c")
        .withBean(VerificationKeys.class)
        .withBean(PhoneHmacService.class)
        .run(context -> assertThat(context).hasSingleBean(PhoneHmacs.class));
  }

  @Test
  void 번호_HMAC_키가_없으면_시작하지_않는다() {
    new ApplicationContextRunner()
        .withPropertyValues("app.keys.verify-code=a", "app.keys.verify-ticket=b")
        .withBean(VerificationKeys.class)
        .withBean(PhoneHmacService.class)
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.keys.phone-hmac"));
  }
}
