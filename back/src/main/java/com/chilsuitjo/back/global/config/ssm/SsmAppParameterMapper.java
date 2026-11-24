package com.chilsuitjo.back.global.config.ssm;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates the small, explicit set of settings the production application consumes. */
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
          Map.entry("app.keys.access-token", "SecureString"),
          Map.entry("app.keys.refresh-token", "SecureString"),
          Map.entry("app.origin", "String"));

  private static final Set<String> VERIFICATION_KEYS =
      Set.of("app.keys.verify-code", "app.keys.verify-ticket", "app.keys.phone-hmac");

  private SsmAppParameterMapper() {}

  static Map<String, Object> toProperties(List<SsmConfigParameter> parameters) {
    if (parameters == null) {
      throw new IllegalStateException("SSM 앱 설정 응답이 없다");
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    for (SsmConfigParameter parameter : parameters) {
      if (parameter == null || parameter.name() == null) {
        throw new IllegalStateException("SSM 앱 설정에 이름이 없는 파라미터가 있다");
      }
      if (!parameter.name().startsWith(PARAMETER_PREFIX)) {
        throw new IllegalStateException("허용하지 않는 SSM 설정 경로: " + parameter.name());
      }
      String property = parameter.name().substring(PARAMETER_PREFIX.length());
      if (!REQUIRED_TYPES.containsKey(property)) {
        throw new IllegalStateException("허용하지 않는 SSM 앱 설정 키: " + property);
      }
      if (properties.containsKey(property)) {
        throw new IllegalStateException("SSM 앱 설정 파라미터가 중복됐다: " + property);
      }
      if (!REQUIRED_TYPES.get(property).equals(parameter.type())) {
        throw new IllegalStateException("SSM 앱 설정 형식이 올바르지 않다: " + property);
      }
      String value = parameter.value();
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("필수 SSM 앱 설정이 비어 있다: " + property);
      }
      validateValue(property, value);
      properties.put(property, value);
    }
    for (String required : REQUIRED_TYPES.keySet()) {
      if (!properties.containsKey(required)) {
        throw new IllegalStateException("필수 SSM 앱 설정이 없다: " + required);
      }
    }
    validateKeys(properties);
    return Map.copyOf(properties);
  }

  private static void validateValue(String property, String value) {
    if ("spring.datasource.url".equals(property) && !value.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("운영 데이터베이스 주소 형식이 올바르지 않다: " + property);
    }
    if ("app.origin".equals(property)) {
      validateOrigin(value);
    }
    if ("app.keys.access-token".equals(property) || "app.keys.refresh-token".equals(property)) {
      if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
        throw new IllegalStateException("운영 토큰 키는 32바이트 이상이어야 한다: " + property);
      }
    }
  }

  private static void validateOrigin(String origin) {
    try {
      URI uri = new URI(origin);
      boolean validScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
      boolean defaultPort =
          ("https".equals(uri.getScheme()) && uri.getPort() == 443)
              || ("http".equals(uri.getScheme()) && uri.getPort() == 80);
      if (!validScheme
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || !uri.getRawPath().isEmpty()
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))
          || defaultPort) {
        throw new IllegalStateException("운영 화면 주소 형식이 올바르지 않다: app.origin");
      }
    } catch (URISyntaxException exception) {
      throw new IllegalStateException("운영 화면 주소 형식이 올바르지 않다: app.origin");
    }
  }

  private static void validateKeys(Map<String, Object> properties) {
    String accessToken = (String) properties.get("app.keys.access-token");
    String refreshToken = (String) properties.get("app.keys.refresh-token");
    if (accessToken.equals(refreshToken)) {
      throw new IllegalStateException("운영 액세스 토큰 키와 리프레시 토큰 키는 달라야 한다");
    }
    String[] verificationKeys =
        VERIFICATION_KEYS.stream().map(key -> (String) properties.get(key)).toArray(String[]::new);
    if (verificationKeys[0].equals(verificationKeys[1])
        || verificationKeys[0].equals(verificationKeys[2])
        || verificationKeys[1].equals(verificationKeys[2])) {
      throw new IllegalStateException("운영 휴대폰 인증 키는 서로 달라야 한다");
    }
  }
}
