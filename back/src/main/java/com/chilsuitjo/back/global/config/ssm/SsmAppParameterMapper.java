package com.chilsuitjo.back.global.config.ssm;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the small, explicit set of settings the production application consumes.
 *
 * <p>Only allowlisted keys reach the Spring Environment. A parameter under the app path whose key
 * is not allowlisted is left out and reported by name only: its value and type are never read. So a
 * value added to Parameter Store before the code that needs it does not stop the running app, and
 * Parameter Store can never set profiles, imports or other Spring behaviour (docs/rules/04 6장).
 */
final class SsmAppParameterMapper {

  static final String PARAMETER_PREFIX = "/homestamp/prod/app/";

  private static final Map<String, String> REQUIRED_TYPES =
      Map.ofEntries(
          Map.entry("spring.datasource.url", "String"),
          Map.entry("spring.datasource.username", "SecureString"),
          Map.entry("spring.datasource.password", "SecureString"),
          Map.entry("app.keys.verify-code", "SecureString"),
          Map.entry("app.keys.verify-ticket", "SecureString"),
          Map.entry("app.keys.phone-hmac", "SecureString"),
          Map.entry("app.keys.upload-ticket", "SecureString"),
          Map.entry("app.keys.link-token", "SecureString"),
          Map.entry("app.keys.access-token", "SecureString"),
          Map.entry("app.keys.refresh-token", "SecureString"),
          Map.entry("app.origin", "String"),
          Map.entry("photo.s3.bucket", "String"));

  /**
   * 있으면 읽고 없어도 되는 설정. 솔라피 키는 {@code app.sms.mode=solapi}로 바꾸기 전에는 넣지 않아도 되고(지금 prod는 disabled), 바꾸면
   * {@code SmsModeGuard}가 비었는지 다시 본다. 있으면 형식과 빈 값은 필수 설정과 같이 검사한다.
   */
  private static final Map<String, String> OPTIONAL_TYPES =
      Map.ofEntries(
          Map.entry("app.sms.solapi.api-key", "SecureString"),
          Map.entry("app.sms.solapi.api-secret", "SecureString"),
          Map.entry("app.sms.solapi.sender", "SecureString"));

  private static final Set<String> VERIFICATION_KEYS =
      Set.of("app.keys.verify-code", "app.keys.verify-ticket", "app.keys.phone-hmac");

  /** Keys that {@code LinkTokenKeys} requires the link token key to differ from. */
  private static final List<String> KEYS_OTHER_THAN_LINK_TOKEN =
      List.of(
          "app.keys.verify-code",
          "app.keys.verify-ticket",
          "app.keys.phone-hmac",
          "app.keys.upload-ticket",
          "app.keys.access-token",
          "app.keys.refresh-token");

  /** Same lower bound as the consumers (AccessTokens, PhotoUploadTicketSigner, LinkTokenKeys). */
  private static final int MIN_KEY_BYTES = 32;

  /** S3 general purpose bucket naming: 3–63 chars of lowercase letters, digits, dots, hyphens. */
  private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

  private static final Pattern JDBC_URL =
      Pattern.compile("jdbc:postgresql://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/[A-Za-z0-9_]+\\?(.+)");

  private static final String SSL_MODE = "sslmode";
  private static final String SSL_ROOT_CERT = "sslrootcert";
  private static final Pattern ROOT_CERT_PATH = Pattern.compile("(/[A-Za-z0-9_][A-Za-z0-9._-]*)+");

  /** Only names made of these characters are echoed back when a key is left out. */
  private static final Pattern REPORTABLE_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  static final String UNREPORTABLE_KEY = "<이름 형식이 다른 키>";

  /**
   * Allowlisted properties and the keys that were left out.
   *
   * @param properties values to put into the Spring Environment
   * @param ignoredKeys names (never values) of app-path parameters that are not allowlisted; a name
   *     outside {@link #REPORTABLE_KEY} is replaced by {@link #UNREPORTABLE_KEY}
   */
  record Mapped(Map<String, Object> properties, List<String> ignoredKeys) {}

  private SsmAppParameterMapper() {}

  static Map<String, Object> toProperties(List<SsmConfigParameter> parameters) {
    return map(parameters).properties();
  }

  static Mapped map(List<SsmConfigParameter> parameters) {
    if (parameters == null) {
      throw new SsmConfigException("SSM 앱 설정 응답이 없다");
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> ignoredKeys = new ArrayList<>();
    for (SsmConfigParameter parameter : parameters) {
      if (parameter == null || parameter.name() == null) {
        throw new SsmConfigException("SSM 앱 설정에 이름이 없는 파라미터가 있다");
      }
      if (!parameter.name().startsWith(PARAMETER_PREFIX)) {
        throw new SsmConfigException("허용하지 않는 SSM 설정 경로: " + parameter.name());
      }
      String property = parameter.name().substring(PARAMETER_PREFIX.length());
      String expectedType =
          REQUIRED_TYPES.containsKey(property)
              ? REQUIRED_TYPES.get(property)
              : OPTIONAL_TYPES.get(property);
      if (expectedType == null) {
        ignoredKeys.add(REPORTABLE_KEY.matcher(property).matches() ? property : UNREPORTABLE_KEY);
        continue;
      }
      if (properties.containsKey(property)) {
        throw new SsmConfigException("SSM 앱 설정 파라미터가 중복됐다: " + property);
      }
      if (!expectedType.equals(parameter.type())) {
        throw new SsmConfigException("SSM 앱 설정 형식이 올바르지 않다: " + property);
      }
      String value = parameter.value();
      if (value == null || value.isBlank()) {
        throw new SsmConfigException("필수 SSM 앱 설정이 비어 있다: " + property);
      }
      validateValue(property, value);
      properties.put(property, value);
    }
    for (String required : REQUIRED_TYPES.keySet()) {
      if (!properties.containsKey(required)) {
        throw new SsmConfigException("필수 SSM 앱 설정이 없다: " + required);
      }
    }
    validateKeys(properties);
    return new Mapped(Map.copyOf(properties), List.copyOf(ignoredKeys));
  }

  private static void validateValue(String property, String value) {
    switch (property) {
      case "spring.datasource.url" -> validateJdbcUrl(value);
      case "app.origin" -> validateOrigin(value);
      case "app.keys.access-token", "app.keys.refresh-token" -> {
        if (bytes(value) < MIN_KEY_BYTES) {
          throw new SsmConfigException("운영 토큰 키는 32바이트 이상이어야 한다: " + property);
        }
      }
      case "app.keys.upload-ticket" -> {
        if (bytes(value) < MIN_KEY_BYTES) {
          throw new SsmConfigException("운영 업로드 표 키는 32바이트 이상이어야 한다: " + property);
        }
      }
      case "app.keys.link-token" -> {
        if (bytes(value) < MIN_KEY_BYTES) {
          throw new SsmConfigException("운영 링크 토큰 키는 32바이트 이상이어야 한다: " + property);
        }
      }
      case "photo.s3.bucket" -> {
        if (!BUCKET_NAME.matcher(value).matches() || value.contains("..")) {
          throw new SsmConfigException("운영 사진 버킷 이름 형식이 올바르지 않다: " + property);
        }
      }
      default -> {}
    }
  }

  /**
   * 주소는 {@code jdbc:postgresql://<host>[:port]/<db>?sslmode=verify-full&sslrootcert=<절대 경로>} 하나의
   * 형태만 받는다(#173 Migration 주소와 같은 규칙, docs/rules/04 "DB 연결").
   *
   * <p>pgjdbc는 URL 속성을 드라이버 속성(사용자·비밀번호)보다 우선하고, 쿼리 값을 URL 디코딩하며, 같은 키는 마지막 값을 쓰고 빈 토큰은 건너뛴다. 그래서
   * 사용자 정보({@code @}), 인코딩 문자({@code %}·{@code +}), 빈 토큰, {@code =} 없는 토큰, 중복 키, 목록 밖 키({@code
   * user}·{@code password}·{@code sslfactory}·{@code sslhostnameverifier}·{@code options}·{@code
   * service} 등)를 모두 거절한다. 인증서는 읽을 수 있는 일반 파일이어야 한다. 메시지에 주소를 넣지 않는다.
   */
  private static void validateJdbcUrl(String url) {
    Matcher matcher = JDBC_URL.matcher(url);
    if (!matcher.matches()) {
      throw new SsmConfigException("운영 데이터베이스 주소 형식이 올바르지 않다: spring.datasource.url");
    }
    String query = matcher.group(1);
    if (query.contains("%") || query.contains("+")) {
      throw new SsmConfigException("운영 데이터베이스 주소에 인코딩된 문자를 쓰지 않는다: spring.datasource.url");
    }
    Map<String, String> options = new HashMap<>();
    for (String token : query.split("&", -1)) {
      int separator = token.indexOf('=');
      if (separator <= 0 || separator == token.length() - 1) {
        throw new SsmConfigException("운영 데이터베이스 주소의 연결 속성 형식이 올바르지 않다: spring.datasource.url");
      }
      String key = token.substring(0, separator);
      if (!key.equals(SSL_MODE) && !key.equals(SSL_ROOT_CERT)) {
        throw new SsmConfigException("운영 데이터베이스 주소에 허용하지 않는 연결 속성이 있다: spring.datasource.url");
      }
      if (options.put(key, token.substring(separator + 1)) != null) {
        throw new SsmConfigException("운영 데이터베이스 주소의 연결 속성이 중복됐다: " + key);
      }
    }
    if (!"verify-full".equals(options.get(SSL_MODE))) {
      throw new SsmConfigException("운영 데이터베이스 연결은 sslmode=verify-full이어야 한다");
    }
    String rootCert = options.get(SSL_ROOT_CERT);
    if (rootCert == null
        || !ROOT_CERT_PATH.matcher(rootCert).matches()
        || rootCert.contains("/../")
        || rootCert.contains("/./")
        || rootCert.endsWith("/..")
        || rootCert.endsWith("/.")) {
      throw new SsmConfigException("운영 데이터베이스 연결의 sslrootcert는 절대 경로여야 한다");
    }
    Path certificate = Path.of(rootCert);
    if (!Files.isRegularFile(certificate) || !Files.isReadable(certificate)) {
      throw new SsmConfigException("운영 데이터베이스 연결의 sslrootcert 파일을 읽을 수 없다");
    }
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static void validateOrigin(String origin) {
    try {
      URI uri = new URI(origin);
      // prod는 https만 받는다. local·test의 http 화면 주소는 이 경로를 거치지 않는다(SSM은 prod만 읽음)
      boolean validScheme = "https".equals(uri.getScheme());
      boolean defaultPort = uri.getPort() == 443;
      if (!validScheme
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))
          || defaultPort) {
        throw new SsmConfigException("운영 화면 주소 형식이 올바르지 않다: app.origin");
      }
    } catch (URISyntaxException exception) {
      throw new SsmConfigException("운영 화면 주소 형식이 올바르지 않다: app.origin");
    }
  }

  private static void validateKeys(Map<String, Object> properties) {
    String accessToken = (String) properties.get("app.keys.access-token");
    String refreshToken = (String) properties.get("app.keys.refresh-token");
    if (accessToken.equals(refreshToken)) {
      throw new SsmConfigException("운영 액세스 토큰 키와 리프레시 토큰 키는 달라야 한다");
    }
    String[] verificationKeys =
        VERIFICATION_KEYS.stream().map(key -> (String) properties.get(key)).toArray(String[]::new);
    if (verificationKeys[0].equals(verificationKeys[1])
        || verificationKeys[0].equals(verificationKeys[2])
        || verificationKeys[1].equals(verificationKeys[2])) {
      throw new SsmConfigException("운영 휴대폰 인증 키는 서로 달라야 한다");
    }
    String linkToken = (String) properties.get("app.keys.link-token");
    for (String other : KEYS_OTHER_THAN_LINK_TOKEN) {
      if (linkToken.equals(properties.get(other))) {
        throw new SsmConfigException("운영 링크 토큰 키는 다른 비밀 키와 달라야 한다: " + other);
      }
    }
  }
}
