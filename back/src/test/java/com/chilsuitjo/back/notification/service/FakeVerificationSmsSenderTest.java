package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import org.junit.jupiter.api.Test;

class FakeVerificationSmsSenderTest {

  private final FakeVerificationSmsSender sender = new FakeVerificationSmsSender();

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
}
