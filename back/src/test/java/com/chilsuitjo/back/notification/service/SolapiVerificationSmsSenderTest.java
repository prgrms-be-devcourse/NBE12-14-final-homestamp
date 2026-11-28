package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.repository.MockSolapi;
import com.chilsuitjo.back.notification.repository.SolapiClient;
import com.chilsuitjo.back.notification.repository.SolapiSettings;
import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 솔라피 인증 문자 발송기를 가짜 솔라피 서버에 붙여 본다(06 ACC-1j). 실제 업체는 부르지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class SolapiVerificationSmsSenderTest {

  private static final String PHONE = "01011112222";
  private static final String CODE = "482915";
  private static final String SEND = "/messages/v4/send-many/detail";

  private final MockSolapi solapi = new MockSolapi();

  @AfterEach
  void close() {
    solapi.close();
  }

  private SolapiVerificationSmsSender sender(Duration responseTimeout) {
    return new SolapiVerificationSmsSender(
        new SolapiClient(
            new SolapiSettings(
                solapi.baseUrl(), "k", "s", "0212345678", Duration.ofSeconds(1), responseTimeout),
            Clock.systemUTC()));
  }

  @Test
  void 접수되면_SENT이고_본문에_인증번호가_있지만_로그에는_번호도_인증번호도_없다(CapturedOutput output) {
    solapi.reply(
        SEND, 200, "{\"groupInfo\":{\"groupId\":\"G1\"},\"messageList\":[{\"messageId\":\"M1\"}]}");

    assertThat(sender(Duration.ofSeconds(2)).send(PHONE, CODE))
        .isEqualTo(VerificationSendResult.SENT);
    assertThat(solapi.received().getFirst().body()).contains(CODE).contains(PHONE);
    assertThat(output).doesNotContain(CODE).doesNotContain(PHONE);
  }

  @Test
  void 답이_늦으면_실제로_갔을_수_있어_UNKNOWN이다() {
    solapi.reply(SEND, new MockSolapi.Reply(200, "{}", Duration.ofMillis(1500)));

    assertThat(sender(Duration.ofMillis(300)).send(PHONE, CODE))
        .isEqualTo(VerificationSendResult.UNKNOWN);
    assertThat(solapi.received()).hasSize(1);
  }

  @Test
  void 업체_5xx는_UNKNOWN이다() {
    solapi.reply(SEND, 500, "{}");

    assertThat(sender(Duration.ofSeconds(2)).send(PHONE, CODE))
        .isEqualTo(VerificationSendResult.UNKNOWN);
  }

  @Test
  void 요청_전체_4xx는_접수_여부를_몰라_UNKNOWN이다() {
    solapi.reply(SEND, 403, "{\"errorCode\":\"Forbidden\"}");

    assertThat(sender(Duration.ofSeconds(2)).send(PHONE, CODE))
        .isEqualTo(VerificationSendResult.UNKNOWN);
  }

  @Test
  void 호출_한도와_등록_실패는_받지_않은_것이_확실해_FAILED다() {
    solapi.reply(SEND, 429, "{\"errorCode\":\"TooManyRequests\"}");
    solapi.reply(
        SEND, 200, "{\"failedMessageList\":[{\"statusCode\":\"3010\",\"statusMessage\":\"x\"}]}");

    SolapiVerificationSmsSender sender = sender(Duration.ofSeconds(2));
    assertThat(sender.send(PHONE, CODE)).isEqualTo(VerificationSendResult.FAILED);
    assertThat(sender.send(PHONE, CODE)).isEqualTo(VerificationSendResult.FAILED);
  }
}
