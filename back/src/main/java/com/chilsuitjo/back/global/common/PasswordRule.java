package com.chilsuitjo.back.global.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * 새 비밀번호 규칙(06 0-1 {@code password.rule}, ACC-1d): 8자 이상, 영문·숫자 포함, UTF-8 {@value #MAX_BYTES}바이트
 * 이하(BCrypt가 받는 최대 길이, decisions 2026-11-19). 가입(member)·비밀번호 변경·재설정(auth)이 같은 규칙을 쓴다(09 3장 "공통").
 * 어긋난 규칙마다 따로 알린다. null은 통과한다(필수 여부는 {@code NotNull}이 본다).
 *
 * <p>규칙을 보지 않는 입력(로그인·현재 비밀번호 확인)도 {@link Bytes#exceedsMax}로 길이만 먼저 본다. 넘는 입력은 BCrypt가 확인하지
 * 못하고(예외), 가입에서 막으므로 맞을 수 없다.
 */
@Documented
@Constraint(validatedBy = {})
@Size(min = 8, message = "비밀번호는 8자 이상이에요")
@Pattern(regexp = "(?s).*[A-Za-z].*", message = "비밀번호에 영문을 넣어 주세요")
@Pattern(regexp = "(?s).*[0-9].*", message = "비밀번호에 숫자를 넣어 주세요")
@MaxUtf8Bytes(value = PasswordRule.MAX_BYTES, message = "비밀번호가 너무 길어요")
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordRule {

  /** BCrypt가 해시에 쓰는 최대 길이(UTF-8 바이트). */
  int MAX_BYTES = 72;

  String message() default "비밀번호를 확인해 주세요";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** 비밀번호 길이 확인(규칙 검사 없이 길이만). */
  final class Bytes {

    private Bytes() {}

    /** UTF-8로 {@link #MAX_BYTES}바이트를 넘는가. */
    public static boolean exceedsMax(String password) {
      return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
  }
}
