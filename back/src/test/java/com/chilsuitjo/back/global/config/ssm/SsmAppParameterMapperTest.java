package com.chilsuitjo.back.global.config.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsmAppParameterMapperTest {

  private static final String PREFIX = "/homestamp/prod/app/";

  private static final List<String> SECURE_KEYS =
      List.of(
          "spring.datasource.username",
          "spring.datasource.password",
          "app.keys.verify-code",
          "app.keys.verify-ticket",
          "app.keys.phone-hmac",
          "app.keys.upload-ticket",
          "app.keys.link-token",
          "app.keys.access-token",
          "app.keys.refresh-token");

  private static final List<String> STRING_KEYS =
      List.of("spring.datasource.url", "app.origin", "photo.s3.bucket");

  @Test
  void 허용된_필수_파라미터를_Spring_속성으로_매핑한다() {
    SsmAppParameterMapper.Mapped mapped = SsmAppParameterMapper.map(requiredParameters());
    Map<String, Object> properties = mapped.properties();

    assertEquals(
        "jdbc:postgresql://db.example:5432/homestamp?sslmode=verify-full&sslrootcert=" + CERT,
        properties.get("spring.datasource.url"));
    assertEquals("app-user", properties.get("spring.datasource.username"));
    assertEquals("db-password-secret", properties.get("spring.datasource.password"));
    assertEquals("test-only-verify-code-key", properties.get("app.keys.verify-code"));
    assertEquals("test-only-verify-ticket-key", properties.get("app.keys.verify-ticket"));
    assertEquals("test-only-phone-hmac-key", properties.get("app.keys.phone-hmac"));
    assertEquals(
        "test-only-photo-upload-ticket-signing-key", properties.get("app.keys.upload-ticket"));
    assertEquals("test-only-link-token-hmac-key-32bytes", properties.get("app.keys.link-token"));
    assertEquals(
        "access-token-secret-which-is-long-enough-0001", properties.get("app.keys.access-token"));
    assertEquals(
        "refresh-token-secret-which-is-long-enough-0002", properties.get("app.keys.refresh-token"));
    assertEquals("https://homestamp.example", properties.get("app.origin"));
    assertEquals("homestamp-photos-test", properties.get("photo.s3.bucket"));
    assertEquals(12, properties.size());
    assertEquals(List.of(), mapped.ignoredKeys());
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
    assertFalse(error.getMessage().contains("other-secret"));
  }

  @Test
  void 목록_밖_키는_적용하지_않고_이름만_돌려주며_기동을_막지_않는다() {
    List<String> outside =
        List.of(
            "spring.profiles.active",
            "spring.config.import",
            "spring.flyway.enabled",
            "app.sms.mode",
            "photo.s3.region");
    List<SsmConfigParameter> parameters = requiredParameters();
    for (String key : outside) {
      parameters.add(new SsmConfigParameter(PREFIX + key, "must-not-apply", "String"));
    }

    SsmAppParameterMapper.Mapped mapped = SsmAppParameterMapper.map(parameters);

    for (String key : outside) {
      assertFalse(mapped.properties().containsKey(key), key);
    }
    assertEquals(12, mapped.properties().size());
    assertEquals(outside, mapped.ignoredKeys());
    assertFalse(mapped.ignoredKeys().toString().contains("must-not-apply"));
  }

  @Test
  void 목록_밖_키는_값과_형식을_읽지_않는다() {
    List<SsmConfigParameter> parameters = requiredParameters();
    // 값이 비었거나 형식이 틀려도, 같은 이름이 두 번 와도 적용하지 않을 키라 실패시키지 않는다
    parameters.add(new SsmConfigParameter(PREFIX + "future.key", null, null));
    parameters.add(new SsmConfigParameter(PREFIX + "future.key", " ", "StringList"));

    SsmAppParameterMapper.Mapped mapped = SsmAppParameterMapper.map(parameters);

    assertEquals(List.of("future.key", "future.key"), mapped.ignoredKeys());
    assertFalse(mapped.properties().containsKey("future.key"));
  }

  @Test
  void 로그에_옮기기_어려운_이름은_대신_표시한다() {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.add(new SsmConfigParameter(PREFIX + "bad name\nwith-newline", "x", "String"));
    parameters.add(new SsmConfigParameter(PREFIX + "", "x", "String"));

    SsmAppParameterMapper.Mapped mapped = SsmAppParameterMapper.map(parameters);

    assertEquals(
        List.of(SsmAppParameterMapper.UNREPORTABLE_KEY, SsmAppParameterMapper.UNREPORTABLE_KEY),
        mapped.ignoredKeys());
  }

  @Test
  void 필수_키_누락_빈값_중복과_잘못된_형식을_거부한다() {
    List<SsmConfigParameter> blank = requiredParameters();
    blank.replaceAll(parameter ->
        parameter.name().endsWith("spring.datasource.password")
            ? new SsmConfigParameter(parameter.name(), "  ", parameter.type())
            : parameter);
    IllegalStateException blankError =
        assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(blank));
    assertFalse(blankError.getMessage().contains("db-password-secret"));

    List<SsmConfigParameter> duplicate = requiredParameters();
    duplicate.add(duplicate.getFirst());
    assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(duplicate));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.datasource.url",
        "spring.datasource.username",
        "spring.datasource.password",
        "app.keys.verify-code",
        "app.keys.verify-ticket",
        "app.keys.phone-hmac",
        "app.keys.upload-ticket",
        "app.keys.link-token",
        "app.keys.access-token",
        "app.keys.refresh-token",
        "app.origin",
        "photo.s3.bucket"
      })
  void 필수_키가_하나라도_없으면_실패한다(String key) {
    List<SsmConfigParameter> missing = requiredParameters();
    missing.removeIf(parameter -> parameter.name().equals(PREFIX + key));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(missing));

    assertEquals("필수 SSM 앱 설정이 없다: " + key, error.getMessage());
  }

  @Test
  void 필수_키의_유형이_다르면_실패한다() {
    for (String key : SECURE_KEYS) {
      assertWrongType(key, "String");
    }
    for (String key : STRING_KEYS) {
      assertWrongType(key, "SecureString");
    }
  }

  @Test
  void 설정_파라미터의_문자열_표현에_비밀값을_넣지_않는다() {
    SsmConfigParameter parameter =
        new SsmConfigParameter(
            PREFIX + "spring.datasource.password", "hidden-secret", "SecureString");

    assertFalse(parameter.toString().contains("hidden-secret"));
    assertTrue(parameter.toString().contains("<redacted>"));
  }

  @Test
  void 운영_필수값의_형식과_키_간_불변식을_검사한다() {
    assertRejected("app.origin", "https://homestamp.example/path", "String", "운영 화면 주소");
    assertRejected(
        "app.keys.refresh-token",
        "access-token-secret-which-is-long-enough-0001",
        "SecureString",
        "운영 액세스 토큰 키와 리프레시 토큰 키는 달라야 한다");
  }

  @Test
  void 업로드_표_키는_32바이트_이상이어야_한다() {
    // PhotoUploadTicketSigner와 같은 하한(UTF-8 바이트 수)
    assertRejected(
        "app.keys.upload-ticket", "x".repeat(31), "SecureString", "운영 업로드 표 키는 32바이트 이상");
    assertAccepted("app.keys.upload-ticket", "x".repeat(32));
  }

  @Test
  void 링크_토큰_키는_32바이트_이상이고_다른_비밀_키와_달라야_한다() {
    // LinkTokenKeys와 같은 조건
    assertRejected("app.keys.link-token", "y".repeat(31), "SecureString", "운영 링크 토큰 키는 32바이트 이상");
    assertAccepted("app.keys.link-token", "y".repeat(32));

    Map<String, String> others =
        Map.of(
            "app.keys.verify-code", "verify-code-secret",
            "app.keys.verify-ticket", "verify-ticket-secret",
            "app.keys.phone-hmac", "phone-hmac-secret",
            "app.keys.upload-ticket", "upload-ticket-secret-which-is-long-enough-0003",
            "app.keys.access-token", "access-token-secret-which-is-long-enough-0001",
            "app.keys.refresh-token", "refresh-token-secret-which-is-long-enough-0002");
    others.forEach((other, value) -> {
      // 짧은 키와 같게 하려면 그 키를 32바이트 이상으로 맞춘 뒤 비교한다
      String shared = value.length() >= 32 ? value : value + "-padded-to-thirty-two-bytes";
      List<SsmConfigParameter> parameters = with(requiredParameters(), other, shared);
      List<SsmConfigParameter> same = with(parameters, "app.keys.link-token", shared);
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(same));
      assertEquals("운영 링크 토큰 키는 다른 비밀 키와 달라야 한다: " + other, error.getMessage());
      assertFalse(error.getMessage().contains(shared));
    });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ab",
        "Homestamp-photos",
        "homestamp_photos",
        "-homestamp",
        "homestamp-",
        "home..stamp",
        "homestamp photos",
        "s3://homestamp-photos"
      })
  void 사진_버킷_이름은_S3_이름_규칙을_따라야_한다(String bucket) {
    assertRejected("photo.s3.bucket", bucket, "String", "운영 사진 버킷 이름 형식");
  }

  @Test
  void 올바른_사진_버킷_이름은_받는다() {
    assertAccepted("photo.s3.bucket", "homestamp-photos");
    assertAccepted("photo.s3.bucket", "abc");
    assertAccepted("photo.s3.bucket", "a".repeat(63));
  }

  private static void assertWrongType(String key, String wrongType) {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.replaceAll(parameter ->
        parameter.name().equals(PREFIX + key)
            ? new SsmConfigParameter(parameter.name(), parameter.value(), wrongType)
            : parameter);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> SsmAppParameterMapper.toProperties(parameters), key);
    assertEquals("SSM 앱 설정 형식이 올바르지 않다: " + key, error.getMessage());
  }

  private static final String CERT = SsmTestParameterSource.ROOT_CERT;
  private static final String DB = "jdbc:postgresql://db.example:5432/homestamp";

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT",
        "jdbc:postgresql://db.example:5432/homestamp",
        "jdbc:postgresql://user:pw@db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=require&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-ca&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslmode=disable&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&sslmode=verify-full",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify%2Dfull&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&sslfactory=org.postgresql.ssl.NonValidatingFactory",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&user=evil",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&password=x",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&sslhostnameverifier=x",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT&ssl",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=relative/root.crt",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=/etc/../etc/root.crt",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=/no/such/root.crt",
        "jdbc:postgresql://db.example,other.example/homestamp?sslmode=verify-full&sslrootcert=@CERT",
        "jdbc:postgresql://db.example/homestamp?sslmode=verify-full&sslrootcert=@CERT#x"
      })
  void 운영_DB_주소는_verify_full과_읽을_수_있는_절대_경로_인증서만_받는다(String template) {
    assertRejected("spring.datasource.url", template.replace("@CERT", CERT), "String", "운영 데이터베이스");
  }

  @Test
  void 운영_DB_주소의_허용_형태() {
    assertAccepted("spring.datasource.url", DB + "?sslmode=verify-full&sslrootcert=" + CERT);
    assertAccepted(
        "spring.datasource.url",
        "jdbc:postgresql://db.example/homestamp?sslrootcert=" + CERT + "&sslmode=verify-full");
  }

  @Test
  void 운영_화면_주소는_https만_받는다() {
    assertRejected("app.origin", "http://homestamp.example", "String", "운영 화면 주소");
    assertRejected("app.origin", "http://localhost:5173", "String", "운영 화면 주소");
    assertRejected("app.origin", "https://homestamp.example:443", "String", "운영 화면 주소");
    assertAccepted("app.origin", "https://homestamp.example");
    assertAccepted("app.origin", "https://homestamp.example:8443");
  }

  private static void assertRejected(String key, String value, String type, String message) {
    List<SsmConfigParameter> parameters = requiredParameters();
    parameters.replaceAll(parameter ->
        parameter.name().equals(PREFIX + key)
            ? new SsmConfigParameter(parameter.name(), value, type)
            : parameter);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> SsmAppParameterMapper.toProperties(parameters),
            key + "=" + value);
    assertTrue(error.getMessage().startsWith(message), error.getMessage());
    assertFalse(error.getMessage().contains(value), "message must not contain the value");
  }

  private static void assertAccepted(String key, String value) {
    Map<String, Object> properties =
        SsmAppParameterMapper.toProperties(with(requiredParameters(), key, value));
    assertEquals(value, properties.get(key));
  }

  private static List<SsmConfigParameter> with(
      List<SsmConfigParameter> base, String key, String value) {
    List<SsmConfigParameter> parameters = new ArrayList<>(base);
    parameters.replaceAll(parameter ->
        parameter.name().equals(PREFIX + key)
            ? new SsmConfigParameter(parameter.name(), value, parameter.type())
            : parameter);
    return parameters;
  }

  private static List<SsmConfigParameter> requiredParameters() {
    return new ArrayList<>(SsmTestParameterSource.validParameters());
  }
}
