package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(OutputCaptureExtension.class)
class FakeVerificationSmsSenderTest {

  private static final String LOG_CODE = "app.sms.log-code";

  private final FakeVerificationSmsSender sender = new FakeVerificationSmsSender(false);

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
  void 로그_설정이_켜져_있으면_인증번호와_가린_번호만_남긴다(CapturedOutput output) {
    new FakeVerificationSmsSender(true).send("01012345678", "482915");

    assertThat(output).contains("482915").contains("010-****-5678").doesNotContain("01012345678");
  }

  @Test
  void 보내지_못했으면_로그_설정이_켜져_있어도_남기지_않는다(CapturedOutput output) {
    FakeVerificationSmsSender logging = new FakeVerificationSmsSender(true);
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

  /** 인증번호 로그는 local 프로필에서만 켜진다. test·prod가 함께 켜진 문서에 들어가면 테스트·운영 로그에 번호가 남는다. */
  @Test
  void 인증번호_로그는_local_전용_문서에서만_켜진다() throws IOException {
    List<PropertySource<?>> docs =
        new YamlPropertySourceLoader().load("main", new ClassPathResource("application.yml"));

    List<String> profilesWithLogCode =
        docs.stream()
            .filter(d -> d.containsProperty(LOG_CODE))
            .map(d -> String.valueOf(d.getProperty("spring.config.activate.on-profile")))
            .toList();

    assertThat(profilesWithLogCode).containsExactly("local & !prod");
  }
}
