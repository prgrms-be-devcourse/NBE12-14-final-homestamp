package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class FakeVerificationSmsSenderTest {

  private static final String LOG_CODE = "app.sms.log-code";

  private final FakeVerificationSmsSender sender = sender(false, "local");

  @Test
  void 기본은_보냄이고_마지막_번호를_알_수_있다() {
    assertThat(sender.send("01012345678", "123456")).isEqualTo(VerificationSendResult.SENT);
    assertThat(sender.lastCodeFor("01012345678")).contains("123456");
  }

  @Test
  void 실패로_정하면_실패를_돌려주고_번호를_남기지_않는다() {
    sender.respondWith(VerificationSendResult.FAILED);

    assertThat(sender.send("01012345678", "123456")).isEqualTo(VerificationSendResult.FAILED);
    assertThat(sender.lastCodeFor("01012345678")).isEmpty();
  }

  @Test
  void 결과_모름은_보낸_것으로_보고_번호를_남긴다() {
    sender.respondWith(VerificationSendResult.UNKNOWN);

    assertThat(sender.send("01012345678", "123456")).isEqualTo(VerificationSendResult.UNKNOWN);
    assertThat(sender.lastCodeFor("01012345678")).contains("123456");
  }

  @Test
  void 로그_설정이_꺼져_있으면_인증번호를_로그에_남기지_않는다(CapturedOutput output) {
    sender.send("01012345678", "482915");

    assertThat(output).doesNotContain("482915");
  }

  @Test
  void 로컬에서_로그_설정이_켜져_있으면_인증번호와_가린_번호만_남긴다(CapturedOutput output) {
    sender(true, "local").send("01012345678", "482915");

    assertThat(output).contains("482915").contains("010-****-5678").doesNotContain("01012345678");
  }

  /** 외부 설정({@code APP_SMS_LOG_CODE=true})으로 켜도 허용된 로컬 개발 환경이 아니면 남기지 않는다(#250 리뷰). */
  @ParameterizedTest
  @ValueSource(strings = {"prod", "test", "local,test", "local,prod", ""})
  void 로컬_개발_환경이_아니면_로그_설정을_켜도_인증번호를_남기지_않는다(String profiles, CapturedOutput output) {
    String[] active = profiles.isEmpty() ? new String[0] : profiles.split(",");
    FakeVerificationSmsSender forced = sender(true, active);

    assertThat(forced.send("01012345678", "482915")).isEqualTo(VerificationSendResult.SENT);

    assertThat(output).doesNotContain("482915").contains("인증번호를 로그에 남기지 않아요");
  }

  @Test
  void 보내지_못했으면_로그_설정이_켜져_있어도_남기지_않는다(CapturedOutput output) {
    FakeVerificationSmsSender logging = sender(true, "local");
    logging.respondWith(VerificationSendResult.FAILED);

    logging.send("01012345678", "482915");

    assertThat(output).doesNotContain("482915");
  }

  @Test
  void 번호는_앞_3자리와_끝_4자리만_보인다() {
    assertThat(FakeVerificationSmsSender.maskPhone("01012345678")).isEqualTo("010-****-5678");
    assertThat(FakeVerificationSmsSender.maskPhone("0101234")).isEqualTo("****");
    assertThat(FakeVerificationSmsSender.maskPhone(null)).isEqualTo("****");
  }

  /**
   * 인증번호 로그는 local 전용 문서에서만 켜지고, 그 문서의 프로필 식은 발송기가 다시 보는 식과 같다. test·prod가 함께 켜진 문서에 들어가면 테스트·운영
   * 로그에 번호가 남는다.
   */
  @Test
  void 인증번호_로그는_local_전용_문서에서만_켜진다() throws IOException {
    List<PropertySource<?>> docs =
        new YamlPropertySourceLoader().load("main", new ClassPathResource("application.yml"));

    List<String> profilesWithLogCode =
        docs.stream()
            .filter(d -> d.containsProperty(LOG_CODE))
            .map(d -> String.valueOf(d.getProperty("spring.config.activate.on-profile")))
            .toList();

    assertThat(profilesWithLogCode).containsExactly(FakeVerificationSmsSender.CODE_LOG_PROFILES);
  }

  private static FakeVerificationSmsSender sender(boolean logCode, String... activeProfiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(activeProfiles);
    return new FakeVerificationSmsSender(logCode, environment);
  }
}
