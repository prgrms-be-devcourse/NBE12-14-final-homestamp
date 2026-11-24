package com.chilsuitjo.back.global.config.ssm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates and maps only the database settings required by the one-shot Migration process. */
final class MigrationSsmParameterMapper {

  static final String PARAMETER_PREFIX = "/homestamp/prod/migration/";

  private static final Map<String, String> REQUIRED_TYPES =
      Map.of(
          "spring.datasource.url", "String",
          "spring.datasource.username", "SecureString",
          "spring.datasource.password", "SecureString",
          "app.migration.owner-role", "String");

  private MigrationSsmParameterMapper() {}

  static Map<String, Object> toProperties(List<SsmConfigParameter> parameters) {
    if (parameters == null) {
      throw new IllegalStateException("Migration SSM 설정 응답이 없다");
    }

    Map<String, Object> properties = new LinkedHashMap<>();
    for (SsmConfigParameter parameter : parameters) {
      if (parameter == null || parameter.name() == null) {
        throw new IllegalStateException("Migration SSM 설정에 이름이 없는 파라미터가 있다");
      }
      if (!parameter.name().startsWith(PARAMETER_PREFIX)) {
        throw new IllegalStateException("Migration 경로 외 SSM 파라미터가 포함됐다");
      }

      String property = parameter.name().substring(PARAMETER_PREFIX.length());
      String expectedType = REQUIRED_TYPES.get(property);
      if (expectedType == null) {
        throw new IllegalStateException("허용하지 않는 Migration SSM 설정 키: " + property);
      }
      if (properties.containsKey(property)) {
        throw new IllegalStateException("Migration SSM 설정 파라미터가 중복됐다: " + property);
      }
      if (!expectedType.equals(parameter.type())) {
        throw new IllegalStateException("Migration SSM 설정 형식이 올바르지 않다: " + property);
      }

      String value = parameter.value();
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("필수 Migration SSM 설정이 비어 있다: " + property);
      }
      validateValue(property, value);
      properties.put(property, value);
    }

    for (String required : REQUIRED_TYPES.keySet()) {
      if (!properties.containsKey(required)) {
        throw new IllegalStateException("필수 Migration SSM 설정이 없다: " + required);
      }
    }
    return Map.copyOf(properties);
  }

  private static void validateValue(String property, String value) {
    if ("spring.datasource.url".equals(property) && !value.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("Migration 데이터베이스 주소 형식이 올바르지 않다");
    }
    if ("app.migration.owner-role".equals(property)
        && !value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
      throw new IllegalStateException("app.migration.owner-role 식별자 형식이 올바르지 않다");
    }
  }
}
