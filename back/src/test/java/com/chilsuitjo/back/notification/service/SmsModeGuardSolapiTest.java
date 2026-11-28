package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.rule.SmsMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@code app.sms.mode=solapi}의 기동 검사. 문구에 설정 값이 아니라 이름만 나오는지도 본다. */
class SmsModeGuardSolapiTest {

  private static MockEnvironment solapi(String profile) {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("app.sms.mode", "solapi")
            .withProperty("app.sms.solapi.api-key", "key-value")
            .withProperty("app.sms.solapi.api-secret", "secret-value")
            .withProperty("app.sms.solapi.sender", "01012345678")
            .withProperty("app.sms.solapi.base-url", "https://api.solapi.com");
    environment.setActiveProfiles(profile);
    return environment;
  }

  @Test
  void prod에서_공식_주소와_키_비밀_발신번호가_있으면_통과한다() {
    assertThat(SmsModeGuard.check(solapi("prod"))).isEqualTo(SmsMode.SOLAPI);
    assertThat(
            SmsModeGuard.check(
                solapi("prod")
                    .withProperty("app.sms.solapi.base-url", "https://api-static.solapi.com")))
        .isEqualTo(SmsMode.SOLAPI);
  }

  @Test
  void 키_비밀_발신번호가_비면_그_이름만_말하고_멈춘다() {
    for (String key :
        new String[] {
          "app.sms.solapi.api-key", "app.sms.solapi.api-secret", "app.sms.solapi.sender"
        }) {
      assertThatThrownBy(() -> SmsModeGuard.check(solapi("prod").withProperty(key, " ")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("솔라피 설정이 없어 시작하지 않는다: " + key);
    }
  }

  @Test
  void 발신번호가_숫자_8에서_11자리가_아니면_값을_말하지_않고_멈춘다() {
    assertThatThrownBy(() ->
            SmsModeGuard.check(
                solapi("prod").withProperty("app.sms.solapi.sender", "010-1234-5678")))
        .hasMessage("솔라피 발신번호는 숫자 8~11자리다: app.sms.solapi.sender");
  }

  @Test
  void prod에서는_공식_주소가_아니거나_http거나_경로_포트가_있으면_멈춘다() {
    for (String url :
        new String[] {
          "https://example.com",
          "http://api.solapi.com",
          "https://api.solapi.com/messages",
          "https://api.solapi.com:8443",
          "https://user@api.solapi.com",
          "not a url"
        }) {
      assertThatThrownBy(
              () -> SmsModeGuard.check(solapi("prod").withProperty("app.sms.solapi.base-url", url)))
          .as(url)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(url);
    }
  }

  @Test
  void 주소가_없으면_기본값으로_실제_업체를_부르지_않고_멈춘다() {
    MockEnvironment noUrl =
        new MockEnvironment()
            .withProperty("app.sms.mode", "solapi")
            .withProperty("app.sms.solapi.api-key", "k")
            .withProperty("app.sms.solapi.api-secret", "s")
            .withProperty("app.sms.solapi.sender", "01012345678");
    noUrl.setActiveProfiles("test");

    assertThatThrownBy(() -> SmsModeGuard.check(noUrl))
        .hasMessageContaining("app.sms.solapi.base-url");
  }

  @Test
  void local_test에서는_가짜_솔라피_서버의_http_주소를_받는다() {
    assertThat(
            SmsModeGuard.check(
                solapi("test").withProperty("app.sms.solapi.base-url", "http://127.0.0.1:18080")))
        .isEqualTo(SmsMode.SOLAPI);
  }
}
