package com.chilsuitjo.back.notification.repository;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 솔라피 HTTP 호출(NOTI-11). 보내기 한 번과 결과 조회를 하고, 응답을 도메인 상태가 아니라 {@link Accepted}·{@link Refused}·{@link
 * NotSent}·{@link Unknown} 넷으로만 돌려준다. 이 값을 통지·인증 결과로 바꾸는 것은 부르는 쪽({@link SolapiSmsGateway}, {@code
 * SolapiVerificationSmsSender})이다. 이 클래스는 DB를 읽거나 쓰지 않는다.
 *
 * <p><b>다시 보내지 않는다.</b> 보내기({@code POST})는 한 번만 부른다. JDK {@link HttpClient}는 {@code POST}를 스스로 다시
 * 보내지 않는다({@code jdk.httpclient.enableAllMethodRetry} 기본 꺼짐). 솔라피에는 멱등 키가 없어서(공식 문서에 없음, {@code
 * allowDuplicates}는 한 요청 안의 같은 수신번호 이야기) 업체가 받았는지 모르는 경우({@link Unknown})를 다시 보내면 두 통이 갈 수 있다.
 *
 * <p>구분(공식 문서 msgstatus·rateLimit, 공식 SDK {@code defaultFetcher.ts}·{@code
 * sendManyDetailResponse.ts}):
 *
 * <ul>
 *   <li>연결하지 못함(연결 거부·연결 시간 초과): 요청이 나가지 않았다 → {@link NotSent}
 *   <li>요청을 보낸 뒤 답이 없음(응답 시간 초과, 연결이 끊김), 5xx, 읽을 수 없는 2xx: 받았는지 모른다 → {@link Unknown}
 *   <li>429: 호출 한도로 요청 전체를 받지 않았다 → {@link Refused}({@link RefusalKind#RATE_LIMITED})
 *   <li>그 밖 4xx·3xx: 요청 전체 오류(인증·잔액·형식). 접수하지 않았다는 공식 근거가 없어 → {@link Unknown}(error 로그)
 *   <li>2xx의 {@code failedMessageList}: 그 문자를 접수하지 않았다. 코드가 계정·발신번호 문제면 {@link
 *       RefusalKind#ACCOUNT}, 아니면 {@link RefusalKind#MESSAGE}
 *   <li>2xx의 {@code messageList}에 메시지 ID가 있음 → {@link Accepted}(메시지 ID와 그룹 ID는 따로 둔다)
 * </ul>
 *
 * <p>다시 시도로 두는 근거(공식 문서 developers/api/messages·guides/message-processing): {@code
 * failedMessageList}는 "등록 실패된 메시지 목록. 여기 목록에 포함되어 있는 메시지는 발송되지 않은 메시지"이고, 접수 단계 데이터 오류(1xxx)는 "메시지
 * 접수는 실패 처리"된다. 그래서 그 문자는 업체에 남지 않아 다시 요청해도 두 통이 되지 않는다. 429는 호출 한도 문서(rateLimit)에 따른 거절이다. 그 밖의 요청
 * 전체 오류는 오류 코드와 접수 여부 문서를 찾지 못해 HTTP 의미만으로 미접수라 단정하지 않고 {@link Unknown}으로 둔다(다시 보내지 않음). 반대로 {@code
 * messageList}의 2000은 접수된 것이라, 잔액·하루 한도로 "발송대기(2000) 상태로 보관"돼도 보낸 것({@link Accepted})으로 두고 다시 보내지
 * 않는다. 같은 예시에서 3040이 "등록되지 않은 발신번호"로 나오는 등 코드와 뜻이 어긋난 곳이 있어, 받는 사람 탓은 확인한 코드({@link
 * #RECIPIENT_CODES})만 인정한다.
 *
 * <p>번호·본문·키·응답 원문은 로그와 예외 문구에 남기지 않는다. 업체 오류 코드는 정해진 글자만 남긴다.
 */
public class SolapiClient {

  private static final Logger log = LoggerFactory.getLogger(SolapiClient.class);

  static final String SEND_PATH = "/messages/v4/send-many/detail";
  static final String LIST_PATH = "/messages/v4/list";

  /** 사용자 정의 칸 이름(05 {@code notice.batch_lead_id}). 키 30자·값 1,000자·10개까지(공식 문서 1032·1035). */
  public static final String BATCH_FIELD = "batchLeadId";

  /** 조회 API 한 쪽 최대(공식 문서 msg-getList {@code limit} 1~500). */
  static final int LIST_LIMIT = 500;

  /** 한 번에 묻는 메시지 ID 수. 주소 길이를 넘지 않게 나눈다. */
  static final int IDS_PER_QUERY = 100;

  /** 한 번 조회에서 따라가는 최대 쪽 수. 업체가 끝을 알리지 않아도 멈춘다. */
  static final int MAX_PAGES = 20;

  /**
   * 받는 번호 탓이라 그 문자만 실패로 둘 접수 거절 코드(공식 문서 msgstatus): 수신번호 형식 오류(3010), 080 수신 거부(1061). 이 밖의 코드는 받는
   * 사람의 실패로 단정하지 않고 {@link RefusalKind#ACCOUNT}로 둔다.
   */
  static final Set<String> RECIPIENT_CODES = Set.of("3010", "1061");

  private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_]{1,40}");
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final SolapiSettings settings;
  private final HttpClient http;
  private final SolapiAuth auth;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public SolapiClient(SolapiSettings settings, Clock clock) {
    this(settings, clock, new SolapiAuth());
  }

  SolapiClient(SolapiSettings settings, Clock clock, SolapiAuth auth) {
    this.settings = settings;
    this.clock = clock;
    this.auth = auth;
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** 보내기 결과. */
  public sealed interface SendOutcome permits Accepted, Refused, NotSent, Unknown {}

  /** 업체가 접수했다. {@code messageId}는 결과 조회 키, {@code groupId}는 묶음(같지 않다). */
  public record Accepted(String messageId, String groupId) implements SendOutcome {}

  /** 업체가 받지 않겠다고 분명히 답했다. {@code code}는 업체 코드(정해진 글자만, 없으면 null). */
  public record Refused(RefusalKind kind, String code) implements SendOutcome {}

  /** 요청이 업체에 나가지 않은 것이 확실하다. */
  public record NotSent() implements SendOutcome {}

  /** 업체가 받았는지 모른다. 다시 보내지 않는다. */
  public record Unknown() implements SendOutcome {}

  public enum RefusalKind {
    /** 그 문자만 접수하지 않음(수신번호 형식 등). */
    MESSAGE,
    /** 계정·인증·잔액·발신번호처럼 우리 쪽 설정 문제. 받는 사람의 실패가 아니다. */
    ACCOUNT,
    /** 호출 한도(429). */
    RATE_LIMITED
  }

  /** 조회한 문자 한 통. {@code batchLeadId}는 보낼 때 넣은 사용자 정의 칸(없으면 null). */
  public record ListedMessage(String messageId, String statusCode, String batchLeadId) {}

  /** 문자 한 통을 보낸다. 예외를 던지지 않는다. */
  public SendOutcome send(String to, String text, Map<String, String> customFields) {
    ObjectNode message = json.createObjectNode();
    message.put("to", to);
    message.put("from", settings.sender());
    message.put("text", text);
    if (!customFields.isEmpty()) {
      ObjectNode fields = message.putObject("customFields");
      customFields.forEach(fields::put);
    }
    ObjectNode body = json.createObjectNode();
    body.putArray("messages").add(message);
    body.put("showMessageList", true);

    HttpRequest request;
    try {
      request = request(SEND_PATH).POST(body(body)).build();
    } catch (RuntimeException e) {
      // 요청을 만들거나 서명하지 못했다. 아무것도 나가지 않았다
      log.error("솔라피 요청을 만들 수 없어 보내지 않음: error={}", e.getClass().getSimpleName());
      return new NotSent();
    }
    HttpResponse<String> response;
    try {
      response = http.send(request, utf8());
    } catch (SSLHandshakeException e) {
      // TLS 연결 전이라 요청이 나가지 않았다. 인증서·주소 설정 문제일 수 있어 운영자가 봐야 한다
      log.error("솔라피 TLS 연결 실패, 보내지 않음");
      return new NotSent();
    } catch (HttpConnectTimeoutException | ConnectException e) {
      log.warn("솔라피 연결 실패, 보내지 않음: error={}", e.getClass().getSimpleName());
      return new NotSent();
    } catch (HttpTimeoutException e) {
      log.warn("솔라피 응답 시간 초과, 접수 여부 모름");
      return new Unknown();
    } catch (IOException e) {
      log.warn("솔라피 호출 중 끊김, 접수 여부 모름: error={}", e.getClass().getSimpleName());
      return new Unknown();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("솔라피 호출 중 중단, 접수 여부 모름");
      return new Unknown();
    }
    return classify(response);
  }

  /**
   * 메시지 ID로 결과를 묻는다. 업체가 돌려준 것만 담는다(없는 ID는 빠짐). 조회가 실패하면(연결·시간 초과·2xx 아님·읽을 수 없음) {@link
   * IllegalStateException}을 던진다. 조회는 다시 해도 문자가 가지 않는다.
   */
  public Map<String, ListedMessage> list(List<String> messageIds) {
    Map<String, ListedMessage> found = new LinkedHashMap<>();
    for (int from = 0; from < messageIds.size(); from += IDS_PER_QUERY) {
      List<String> chunk =
          messageIds.subList(from, Math.min(from + IDS_PER_QUERY, messageIds.size()));
      String startKey = null;
      for (int page = 0; page < MAX_PAGES; page++) {
        JsonNode root = listPage(chunk, startKey);
        JsonNode messages = root.path("messageList");
        // 공식 문서: messageList는 메시지 ID를 키로 하는 객체다(배열이 아님)
        for (var entry : messages.properties()) {
          JsonNode m = entry.getValue();
          String id = text(m, "messageId");
          if (id == null) {
            id = entry.getKey();
          }
          found.put(
              id,
              new ListedMessage(
                  id, text(m, "statusCode"), text(m.path("customFields"), BATCH_FIELD)));
        }
        startKey = text(root, "nextKey");
        if (startKey == null) {
          break;
        }
      }
    }
    return found;
  }

  private JsonNode listPage(List<String> ids, String startKey) {
    StringBuilder query = new StringBuilder(LIST_PATH);
    query.append("?limit=").append(LIST_LIMIT);
    query.append("&messageIds=").append(encode(json.writeValueAsString(ids)));
    if (startKey != null) {
      query.append("&startKey=").append(encode(startKey));
    }
    HttpResponse<String> response;
    try {
      response = http.send(request(query.toString()).GET().build(), utf8());
    } catch (IOException e) {
      throw new IllegalStateException("솔라피 결과 조회 실패: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("솔라피 결과 조회 중단");
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "솔라피 결과 조회 실패: status=" + response.statusCode() + ", code=" + errorCode(response.body()));
    }
    try {
      JsonNode root = json.readTree(response.body());
      if (!root.isObject()) {
        throw new IllegalStateException("솔라피 결과 조회 응답을 읽을 수 없어요");
      }
      return root;
    } catch (JacksonException e) {
      throw new IllegalStateException("솔라피 결과 조회 응답을 읽을 수 없어요");
    }
  }

  private SendOutcome classify(HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 429) {
      log.warn("솔라피 호출 한도로 받지 않음(429)");
      return new Refused(RefusalKind.RATE_LIMITED, "TooManyRequests");
    }
    if (status >= 500) {
      log.warn("솔라피 서버 오류, 접수 여부 모름: status={}", status);
      return new Unknown();
    }
    if (status / 100 != 2) {
      // 요청 전체 오류(인증·형식·잔액 등). 이 응답이 접수하지 않았다는 뜻인지는 공식 문서에 없어 HTTP 의미만으로 단정하지 않는다.
      // 다시 보내면 두 통이 될 수 있어 결과 모름으로 두고, 우리 쪽 문제일 가능성이 커서 운영자가 보게 error로 남긴다
      log.error(
          "솔라피가 요청 전체를 오류로 답함, 접수 여부 모름(계정·설정 확인): status={}, code={}",
          status,
          errorCode(response.body()));
      return new Unknown();
    }
    JsonNode root;
    try {
      root = json.readTree(response.body());
    } catch (JacksonException e) {
      log.warn("솔라피 응답을 읽을 수 없어 접수 여부 모름");
      return new Unknown();
    }
    JsonNode failed = root.path("failedMessageList");
    if (failed.isArray() && !failed.isEmpty()) {
      String code = safe(text(failed.get(0), "statusCode"), SAFE_CODE);
      if (code != null && RECIPIENT_CODES.contains(code)) {
        log.warn("솔라피가 받는 번호 문제로 문자를 접수하지 않음: code={}", code);
        return new Refused(RefusalKind.MESSAGE, code);
      }
      // 받는 번호 탓이라고 확인한 코드가 아니면 받는 사람의 실패로 적지 않는다(계정·발신번호·모르는 코드). 운영자가 봐야 한다
      log.error("솔라피가 문자를 접수하지 않음(계정·발신번호 또는 모르는 코드, 설정 확인): code={}", code);
      return new Refused(RefusalKind.ACCOUNT, code);
    }
    String groupId = safe(text(root.path("groupInfo"), "groupId"), SAFE_ID);
    JsonNode list = root.path("messageList");
    if (list.isArray()) {
      for (JsonNode m : list) {
        String id = safe(text(m, "messageId"), SAFE_ID);
        if (id != null) {
          return new Accepted(id, groupId);
        }
      }
    }
    log.warn("솔라피 응답에 메시지 ID가 없어 접수 여부 모름: group={}", groupId);
    return new Unknown();
  }

  private HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(settings.baseUrl().toString() + pathAndQuery))
        .timeout(settings.responseTimeout())
        .header(
            "Authorization", auth.header(settings.apiKey(), settings.apiSecret(), clock.instant()))
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Accept", "application/json");
  }

  private HttpRequest.BodyPublisher body(JsonNode body) {
    return HttpRequest.BodyPublishers.ofString(
        json.writeValueAsString(body), StandardCharsets.UTF_8);
  }

  private static HttpResponse.BodyHandler<String> utf8() {
    return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
  }

  private String errorCode(String body) {
    try {
      return safe(text(json.readTree(body), "errorCode"), SAFE_CODE);
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /** 정해진 글자가 아니면 남기지 않는다(응답 값이 로그에 그대로 찍히지 않게). */
  static String safe(String value, Pattern pattern) {
    return Optional.ofNullable(value).filter(v -> pattern.matcher(v).matches()).orElse(null);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
