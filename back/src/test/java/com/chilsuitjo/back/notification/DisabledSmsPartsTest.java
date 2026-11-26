package com.chilsuitjo.back.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.repository.DisabledSmsGateway;
import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import com.chilsuitjo.back.notification.service.DisabledVerificationSmsSender;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 문자가 꺼진 부품은 보낸 것으로 답하지 않고, 번호·본문·인증번호를 로그에 남기지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class DisabledSmsPartsTest {

  private static final String PHONE = "01055554444";

  @Test
  void 통지_문자는_최종_실패로_답한다(CapturedOutput output) {
    SmsSendResult result =
        new DisabledSmsGateway().send(new SmsMessage(UUID.randomUUID(), PHONE, "본문-비밀"));

    assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.REJECTED);
    assertThat(result.providerMessageId()).isNull();
    assertThat(output).doesNotContain(PHONE).doesNotContain("본문-비밀");
  }

  @Test
  void 인증_문자는_실패로_답한다(CapturedOutput output) {
    assertThat(new DisabledVerificationSmsSender().send(PHONE, "123456"))
        .isEqualTo(VerificationSendResult.FAILED);
    assertThat(output).doesNotContain(PHONE).doesNotContain("123456");
  }
}
