package com.chilsuitjo.back.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.dto.DeliveryQuery;
import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import com.chilsuitjo.back.notification.dto.SmsSendResult.Outcome;
import com.chilsuitjo.back.notification.rule.DeliveryOutcome;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 솔라피 발송기를 가짜 솔라피 서버(HTTP)에 붙여 본다. 실제 업체는 부르지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class SolapiSmsGatewayTest {

  private static final String PHONE = "01098765432";
  private static final String TEXT = "[홈스탬프] 비밀 본문";
  private static final String SECRET = "test-only-secret-value";
  private static final String SEND = SolapiClient.SEND_PATH;
  private static final String LIST = SolapiClient.LIST_PATH;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC);

  private final MockSolapi solapi = new MockSolapi();
  private final JsonMapper json = JsonMapper.builder().build();

  @AfterEach
  void close() {
    solapi.close();
  }

  private SolapiSmsGateway gateway(URI baseUrl, Duration responseTimeout) {
    SolapiSettings settings =
        new SolapiSettings(
            baseUrl, "test-key", SECRET, "0212345678", Duration.ofSeconds(1), responseTimeout);
    return new SolapiSmsGateway(new SolapiClient(settings, CLOCK));
  }

  private SolapiSmsGateway gateway() {
    return gateway(solapi.baseUrl(), Duration.ofSeconds(2));
  }

  private static SmsMessage message(UUID batch) {
    return new SmsMessage(batch, PHONE, TEXT);
  }

  private static String accepted(String messageId, String groupId) {
    return """
    {"groupInfo":{"groupId":"%s","count":{"total":1,"registeredFailed":0}},
     "failedMessageList":[],
     "messageList":[{"messageId":"%s","statusCode":"2000","statusMessage":"정상 접수"}]}
    """
        .formatted(groupId, messageId);
  }

  private static String failed(String code) {
    return """
    {"groupInfo":{"groupId":"G1","count":{"total":1,"registeredFailed":1}},
     "failedMessageList":[{"to":"%s","statusCode":"%s","statusMessage":"실패"}]}
    """
        .formatted(PHONE, code);
  }

  // ---- 보내기 ----

  @Test
  void 접수되면_그룹_ID가_아닌_메시지_ID로_SENT이고_요청은_한_번_서명해_묶음_ID를_사용자_정의_칸에_넣는다(CapturedOutput output) {
    UUID batch = UUID.randomUUID();
    solapi.reply(SEND, 200, accepted("M4V-message", "G4V-group"));

    SmsSendResult result = gateway().send(message(batch));

    assertThat(result).isEqualTo(SmsSendResult.sent("M4V-message"));
    assertThat(solapi.received()).hasSize(1);
    MockSolapi.Received request = solapi.received().getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/messages/v4/send-many/detail");
    assertThat(request.authorization())
        .startsWith("HMAC-SHA256 apiKey=test-key, date=2026-11-28T00:00:00Z, salt=")
        .doesNotContain(SECRET);
    JsonNode body = json.readTree(request.body());
    JsonNode sent = body.path("messages").get(0);
    assertThat(sent.path("to").asString()).isEqualTo(PHONE);
    assertThat(sent.path("from").asString()).isEqualTo("0212345678");
    assertThat(sent.path("text").asString()).isEqualTo(TEXT);
    assertThat(sent.path("customFields").path("batchLeadId").asString())
        .isEqualTo(batch.toString());
    assertThat(body.path("messages")).hasSize(1);
    assertThat(output).doesNotContain(PHONE).doesNotContain(TEXT).doesNotContain(SECRET);
  }

  @Test
  void 받는_번호_형식처럼_그_문자만_거절하면_REJECTED다() {
    solapi.reply(SEND, 200, failed("3010"));

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.REJECTED);
  }

  @Test
  void 발신번호_미등록이나_잔액_부족은_받는_사람의_실패가_아니라_통지를_실패로_두지_않고_RETRY다(CapturedOutput output) {
    for (String code : List.of("1062", "2062", "1030", "2230", "1020")) {
      solapi.reply(SEND, 200, failed(code));
      assertThat(gateway().send(message(UUID.randomUUID())).outcome())
          .as(code)
          .isEqualTo(Outcome.RETRY);
    }
    assertThat(output).contains("계정·발신번호").doesNotContain(PHONE);
  }

  @Test
  void 받는_번호_탓으로_확인하지_않은_모르는_코드나_코드_없는_거절은_받는_사람의_실패로_단정하지_않고_RETRY다(CapturedOutput output) {
    solapi.reply(SEND, 200, failed("1099"));
    solapi.reply(SEND, 200, "{\"failedMessageList\":[{\"statusMessage\":\"x\"}]}");

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.RETRY);
    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.RETRY);
    assertThat(output).contains("모르는 코드");
  }

  @Test
  void 수신_거부_1061도_그_문자만의_실패라_REJECTED다() {
    solapi.reply(SEND, 200, failed("1061"));

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.REJECTED);
  }

  @Test
  void 인증_실패처럼_요청_전체가_4xx면_접수_여부를_몰라_UNKNOWN이고_다시_보내지_않는다(CapturedOutput output) {
    solapi.reply(
        SEND, 401, "{\"errorCode\":\"InvalidAPIKey\",\"errorMessage\":\"" + PHONE + " 키 오류\"}");

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(solapi.received()).hasSize(1);
    // 업체 문구는 남기지 않고 정해진 글자의 코드만 남긴다
    assertThat(output).contains("code=InvalidAPIKey").doesNotContain(PHONE);
  }

  @Test
  void 호출_한도_429는_받지_않은_것이라_RETRY다() {
    solapi.reply(SEND, 429, "{\"errorCode\":\"TooManyRequests\",\"message\":\"잦은 호출로 제한되었습니다.\"}");

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.RETRY);
    solapi.reply(SEND, 429, "{\"errorCode\":\"TooManyRequests\"}");
    SolapiClient client =
        new SolapiClient(
            new SolapiSettings(
                solapi.baseUrl(),
                "test-key",
                SECRET,
                "0212345678",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)),
            CLOCK);
    // 다시 시도는 같아도 분류는 계정 오류가 아니라 호출 한도다
    assertThat(client.send(PHONE, "x", Map.of()))
        .isEqualTo(
            new SolapiClient.Refused(SolapiClient.RefusalKind.RATE_LIMITED, "TooManyRequests"));
  }

  @Test
  void 업체_5xx는_받았는지_몰라_UNKNOWN이고_다시_보내지_않는다() {
    solapi.reply(SEND, 502, "bad gateway");

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(solapi.received()).hasSize(1);
  }

  @Test
  void 읽을_수_없는_2xx나_메시지_ID가_없는_2xx는_UNKNOWN이다() {
    solapi.reply(SEND, 200, "not json");
    solapi.reply(SEND, 200, "{\"groupInfo\":{\"groupId\":\"G1\"},\"failedMessageList\":[]}");

    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(gateway().send(message(UUID.randomUUID())).outcome()).isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void 보낸_뒤_답이_늦으면_UNKNOWN이고_POST를_스스로_다시_보내지_않는다() {
    solapi.reply(
        SEND, new MockSolapi.Reply(200, accepted("M-late", "G-late"), Duration.ofMillis(1500)));

    SmsSendResult result =
        gateway(solapi.baseUrl(), Duration.ofMillis(300)).send(message(UUID.randomUUID()));

    assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(solapi.received()).hasSize(1);
  }

  @Test
  void 연결하지_못하면_나가지_않은_것이_확실해_RETRY다() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    SmsSendResult result =
        gateway(URI.create("http://127.0.0.1:" + closedPort), Duration.ofSeconds(1))
            .send(message(UUID.randomUUID()));

    assertThat(result.outcome()).isEqualTo(Outcome.RETRY);
  }

  // ---- 결과 조회 ----

  @Test
  void 메시지_ID로_묻고_4000은_전달됨_3000을_뺀_3xxx는_실패_나머지와_없는_것은_결과_없음이다() {
    UUID delivered = UUID.randomUUID();
    UUID failed = UUID.randomUUID();
    UUID carrier = UUID.randomUUID();
    UUID accepted = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    solapi.reply(
        LIST,
        200,
        """
        {"limit":500,"messageList":{
          "M1":{"messageId":"M1","statusCode":"4000","customFields":{"batchLeadId":"%s"}},
          "M2":{"messageId":"M2","statusCode":"3059","customFields":{"batchLeadId":"%s"}},
          "M3":{"messageId":"M3","statusCode":"3000","customFields":{"batchLeadId":"%s"}},
          "M4":{"messageId":"M4","statusCode":"2000","customFields":{"batchLeadId":"%s"}}}}
        """
            .formatted(delivered, failed, carrier, accepted));

    Map<UUID, DeliveryOutcome> results =
        gateway()
            .queryResults(
                List.of(
                    new DeliveryQuery(delivered, "M1"),
                    new DeliveryQuery(failed, "M2"),
                    new DeliveryQuery(carrier, "M3"),
                    new DeliveryQuery(accepted, "M4"),
                    new DeliveryQuery(missing, "M5")));

    assertThat(results)
        .containsExactly(
            Map.entry(delivered, DeliveryOutcome.DELIVERED),
            Map.entry(failed, DeliveryOutcome.FAILED),
            Map.entry(carrier, DeliveryOutcome.NONE),
            Map.entry(accepted, DeliveryOutcome.NONE),
            Map.entry(missing, DeliveryOutcome.NONE));
    MockSolapi.Received request = solapi.received().getFirst();
    assertThat(request.method()).isEqualTo("GET");
    assertThat(URLDecoder.decode(request.query(), StandardCharsets.UTF_8))
        .contains("messageIds=[\"M1\",\"M2\",\"M3\",\"M4\",\"M5\"]")
        .contains("limit=500");
  }

  @Test
  void 업체_서버_오류나_발신번호_형식처럼_받는_사람_탓이_아닌_3xxx도_보내지_못한_사실이라_실패이고_운영_로그를_남긴다(CapturedOutput output) {
    UUID server = UUID.randomUUID();
    UUID sender = UUID.randomUUID();
    solapi.reply(
        LIST,
        200,
        """
        {"messageList":{
          "M1":{"messageId":"M1","statusCode":"3024","customFields":{"batchLeadId":"%s"}},
          "M2":{"messageId":"M2","statusCode":"3013","customFields":{"batchLeadId":"%s"}}}}
        """
            .formatted(server, sender));

    assertThat(
            gateway()
                .queryResults(
                    List.of(new DeliveryQuery(server, "M1"), new DeliveryQuery(sender, "M2"))))
        .containsEntry(server, DeliveryOutcome.FAILED)
        .containsEntry(sender, DeliveryOutcome.FAILED);
    assertThat(output).contains("code=3024").contains("code=3013");
  }

  @Test
  void 묶음_ID가_다른_결과는_다른_문자의_결과라_전달됨으로_쓰지_않는다() {
    UUID batch = UUID.randomUUID();
    solapi.reply(
        LIST,
        200,
        """
        {"messageList":{"M1":{"messageId":"M1","statusCode":"4000","customFields":{"batchLeadId":"%s"}}}}
        """
            .formatted(UUID.randomUUID()));

    assertThat(gateway().queryResults(List.of(new DeliveryQuery(batch, "M1"))))
        .containsEntry(batch, DeliveryOutcome.NONE);
  }

  @Test
  void 메시지_ID가_없는_문자는_업체에_묻지_않고_결과_없음이다() {
    UUID batch = UUID.randomUUID();

    assertThat(gateway().queryResults(List.of(new DeliveryQuery(batch, null))))
        .containsExactly(Map.entry(batch, DeliveryOutcome.NONE));
    assertThat(solapi.received()).isEmpty();
  }

  @Test
  void 다음_쪽이_있으면_nextKey로_이어서_묻는다() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    solapi.reply(
        LIST,
        200,
        "{\"nextKey\":\"K2\",\"messageList\":{\"M1\":{\"messageId\":\"M1\",\"statusCode\":\"4000\"}}}");
    solapi.reply(
        LIST, 200, "{\"messageList\":{\"M2\":{\"messageId\":\"M2\",\"statusCode\":\"3040\"}}}");

    Map<UUID, DeliveryOutcome> results =
        gateway()
            .queryResults(List.of(new DeliveryQuery(first, "M1"), new DeliveryQuery(second, "M2")));

    assertThat(results)
        .containsEntry(first, DeliveryOutcome.DELIVERED)
        .containsEntry(second, DeliveryOutcome.FAILED);
    assertThat(solapi.received()).hasSize(2);
    assertThat(solapi.received().get(1).query()).contains("startKey=K2");
  }

  @Test
  void 조회가_실패하면_결과_없음으로_숨기지_않고_예외다() {
    solapi.reply(LIST, 503, "{\"errorCode\":\"ServiceUnavailable\"}");

    assertThatThrownBy(
            () -> gateway().queryResults(List.of(new DeliveryQuery(UUID.randomUUID(), "M1"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("status=503");
  }

  @Test
  void 설정의_toString은_키_비밀_발신번호를_보이지_않는다() {
    SolapiSettings settings =
        new SolapiSettings(
            solapi.baseUrl(),
            "test-key",
            SECRET,
            "0212345678",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));

    assertThat(settings.toString())
        .doesNotContain("test-key")
        .doesNotContain(SECRET)
        .doesNotContain("0212345678");
  }
}
