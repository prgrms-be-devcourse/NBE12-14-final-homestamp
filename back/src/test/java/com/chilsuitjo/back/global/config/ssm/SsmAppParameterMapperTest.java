package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SsmAppParameterMapperTest {

  private static final String PREFIX = "/homestamp/prod/app/";

  @Test
  void 허용된_필수_파라미터를_Spring_속성으로_매핑한다() {
    Map<String, Object> properties = SsmAppParameterMapper.toProperties(requiredParameters());

    assertEquals(
        "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full",
        properties.get("spring.datasource.url"));
    assertEquals("app-user", properties.get("spring.datasource.username"));
    assertEquals("db-password-secret", properties.get("spring.datasource.password"));
    assertEquals("verify-code-secret", properties.get("app.keys.verify-code"));
    assertEquals("verify-ticket-secret", properties.get("app.keys.verify-ticket"));
    assertEquals("phone-hmac-secret", properties.get("app.keys.phone-hmac"));
    assertEquals(
        "access-token-secret-which-is-long-enough-0001", properties.get("app.keys.access-token"));
    assertEquals(
        "refresh-token-secret-which-is-long-enough-0002", properties.get("app.keys.refresh-token"));
    assertEquals("https://homestamp.example", properties.get("app.origin"));
    assertEquals(9, properties.size());
  }

  @Test
  void 잘못된_프로필_경로는_값을_환경에_추가하지_않고_실패한다() {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.add(
        new SsmConfigParameter(
            "/homestamp/prod/migration/spring.datasource.password",
            "other-secret",
            "SecureString"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(parameters));

    assertTrue(error.getMessage().contains("/homestamp/prod/migration/spring.datasource.password"));
    assertTrue(!error.getMessage().contains("other-secret"));
  }

  @Test
  void allowlist에_없는_내부_Spring_설정은_적용하지_않고_실패한다() {
    for (String protectedKey :
        List.of("spring.profiles.active", "spring.config.import", "spring.flyway.enabled")) {
      List<SsmConfigParameter> parameters = requiredParameters();
      parameters.add(new SsmConfigParameter(PREFIX + protectedKey, "must-not-apply", "String"));

      IllegalStateException error =
          assertThrows(
              IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(parameters));

      assertTrue(error.getMessage().contains(protectedKey));
      assertTrue(!error.getMessage().contains("must-not-apply"));
    }
  }

  @Test
  void 필수_키_누락_빈값_중복과_잘못된_형식을_거부한다() {
    List<SsmConfigParameter> missing = requiredParameters();
    missing.removeIf(parameter -> parameter.name().endsWith("app.origin"));
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(missing));

    List<SsmConfigParameter> blank = requiredParameters();
    blank.replaceAll(parameter ->
        parameter.name().endsWith("spring.datasource.password")
            ? new SsmConfigParameter(parameter.name(), "  ", parameter.type())
            : parameter);
    IllegalStateException blankError =
        assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(blank));
    assertTrue(!blankError.getMessage().contains("db-password-secret"));

    List<SsmConfigParameter> duplicate = requiredParameters();
    duplicate.add(duplicate.getFirst());
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(duplicate));

    List<SsmConfigParameter> wrongType = requiredParameters();
    wrongType.replaceAll(parameter ->
        parameter.name().endsWith("app.keys.access-token")
            ? new SsmConfigParameter(parameter.name(), parameter.value(), "String")
            : parameter);
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(wrongType));
  }

  @Test
  void 설정_파라미터의_문자열_표현에_비밀값을_넣지_않는다() {
    SsmConfigParameter parameter =
        new SsmConfigParameter(
            PREFIX + "spring.datasource.password", "hidden-secret", "SecureString");

    assertTrue(!parameter.toString().contains("hidden-secret"));
    assertTrue(parameter.toString().contains("<redacted>"));
  }

  @Test
  void 운영_필수값의_형식과_키_간_불변식을_검사한다() {
    List<SsmConfigParameter> badOrigin = requiredParameters();
    badOrigin.replaceAll(parameter ->
        parameter.name().endsWith("app.origin")
            ? new SsmConfigParameter(parameter.name(), "https://homestamp.example/path", "String")
            : parameter);
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(badOrigin));

    List<SsmConfigParameter> sameKeys = requiredParameters();
    sameKeys.replaceAll(parameter ->
        parameter.name().endsWith("app.keys.refresh-token")
            ? new SsmConfigParameter(
                parameter.name(), "access-token-secret-which-is-long-enough-0001", "SecureString")
            : parameter);
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(sameKeys));
  }

  private static List<SsmConfigParameter> requiredParameters() {
    List<SsmConfigParameter> parameters = new ArrayList<>();
    parameters.add(
        parameter(
            "spring.datasource.url",
            "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full",
            "String"));
    parameters.add(parameter("spring.datasource.username", "app-user", "SecureString"));
    parameters.add(parameter("spring.datasource.password", "db-password-secret", "SecureString"));
    parameters.add(parameter("app.keys.verify-code", "verify-code-secret", "SecureString"));
    parameters.add(parameter("app.keys.verify-ticket", "verify-ticket-secret", "SecureString"));
    parameters.add(parameter("app.keys.phone-hmac", "phone-hmac-secret", "SecureString"));
    parameters.add(
        parameter(
            "app.keys.access-token",
            "access-token-secret-which-is-long-enough-0001",
            "SecureString"));
    parameters.add(
        parameter(
            "app.keys.refresh-token",
            "refresh-token-secret-which-is-long-enough-0002",
            "SecureString"));
    parameters.add(parameter("app.origin", "https://homestamp.example", "String"));
    return parameters;
  }

  private static SsmConfigParameter parameter(String property, String value, String type) {
    return new SsmConfigParameter(PREFIX + property, value, type);
  }
}
