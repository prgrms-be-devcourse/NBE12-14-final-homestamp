package com.chilsuitjo.back.member.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 가입 요청의 입력 규칙(06 ACC-1d, 05 {@code member} 칸 크기). 확인 항목은 {@code SignupAcknowledgementsTest}가 본다.
 */
class SignupRequestTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "abc12345, true", // 06 ACC-1d: 가입된다
    "abcdefgh, false", // 06 ACC-1d: 숫자 없음
    "1234567, false", // 06 ACC-1d: 8자 미만
    "12345678, false", // 06 0-1 password.rule: 영문 없음
    "가나다라마바사1, false", // 영문은 A~Z·a~z
  })
  void ACC_1d_비밀번호_규칙(String password, boolean valid) {
    assertThat(invalidFields(request("a@x.com", password, "정서윤")).contains("password"))
        .isEqualTo(!valid);
  }

  @Test
  void 비밀번호는_UTF_8_72바이트까지다_영문_숫자() {
    String at72 = "a1".repeat(36);
    assertThat(bytes(at72)).isEqualTo(72);
    assertThat(invalidFields(request("a@x.com", at72, "정서윤"))).doesNotContain("password");
    assertThat(invalidFields(request("a@x.com", at72 + "b", "정서윤"))).contains("password");
  }

  @Test
  void 비밀번호_길이는_글자_수가_아니라_바이트로_센다_한글은_한_글자에_3바이트() {
    String at72 = "가".repeat(23) + "a12"; // 69 + 3 = 72바이트, 26글자
    String at73 = "가".repeat(23) + "a123"; // 73바이트, 27글자
    assertThat(bytes(at72)).isEqualTo(72);
    assertThat(bytes(at73)).isEqualTo(73);

    assertThat(invalidFields(request("a@x.com", at72, "정서윤"))).doesNotContain("password");
    assertThat(invalidFields(request("a@x.com", at73, "정서윤"))).contains("password");
  }

  @Test
  void 이모지는_한_글자에_4바이트다() {
    String at72 = "😀".repeat(17) + "a123"; // 68 + 4 = 72바이트
    String at73 = "😀".repeat(17) + "a1234";
    assertThat(bytes(at72)).isEqualTo(72);

    assertThat(invalidFields(request("a@x.com", at72, "정서윤"))).doesNotContain("password");
    assertThat(invalidFields(request("a@x.com", at73, "정서윤"))).contains("password");
  }

  @Test
  void 비밀번호가_없으면_입력_오류다() {
    assertThat(invalidFields(request("a@x.com", null, "정서윤"))).contains("password");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "not-an-email"})
  void 이메일이_비었거나_이메일_모양이_아니면_입력_오류다(String email) {
    assertThat(invalidFields(request(email, "abc12345", "정서윤"))).contains("email");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void 이름이_비었으면_입력_오류다(String name) {
    assertThat(invalidFields(request("a@x.com", "abc12345", name))).contains("displayName");
  }

  @Test
  void 이름은_DB_칸_크기_50자까지다() {
    assertThat(invalidFields(request("a@x.com", "abc12345", "가".repeat(50))))
        .doesNotContain("displayName");
    assertThat(invalidFields(request("a@x.com", "abc12345", "가".repeat(51))))
        .contains("displayName");
  }

  @Test
  void 인증_표가_없으면_입력_오류다() {
    SignupRequest request =
        new SignupRequest("a@x.com", "abc12345", "정서윤", " ", true, "v1", true, "v1", true, "v1");
    assertThat(invalidFields(request)).contains("verificationTicket");
  }

  @Test
  void 확인_항목이_빠져도_입력_오류로_막지_않는다_ACC_1a의_코드는_따로_정한다() {
    SignupRequest request =
        new SignupRequest(
            "a@x.com", "abc12345", "정서윤", "ticket", null, null, null, null, null, null);
    assertThat(invalidFields(request)).isEmpty();
  }

  @Test
  void 문자열로_바꿔도_비밀번호와_인증_표가_나오지_않는다() {
    SignupRequest request =
        new SignupRequest(
            "a@x.com", "secret123", "정서윤", "ticket-value", true, "v1", true, "v1", true, "v1");
    assertThat(request.toString()).doesNotContain("secret123").doesNotContain("ticket-value");
  }

  private static int bytes(String text) {
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  private static SignupRequest request(String email, String password, String name) {
    return new SignupRequest(email, password, name, "ticket", true, "v1", true, "v1", true, "v1");
  }

  private static Set<String> invalidFields(SignupRequest request) {
    return validator.validate(request).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }
}
