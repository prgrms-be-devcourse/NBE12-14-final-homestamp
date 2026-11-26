package com.chilsuitjo.back.member.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 새 비밀번호 규칙(06 0-1 {@code password.rule}, ACC-1d): 8자 이상, 영문·숫자 포함, UTF-8 {@value
 * SignupRequest#PASSWORD_MAX_BYTES}바이트 이하(BCrypt가 받는 최대 길이, decisions 2026-11-19). 가입·비밀번호 변경·재설정이
 * 같은 규칙을 쓴다. 어긋난 규칙마다 따로 알린다. null은 통과한다(필수 여부는 {@code NotNull}이 본다).
 */
@Documented
@Constraint(validatedBy = {})
@Size(min = 8, message = "비밀번호는 8자 이상이에요")
@Pattern(regexp = "(?s).*[A-Za-z].*", message = "비밀번호에 영문을 넣어 주세요")
@Pattern(regexp = "(?s).*[0-9].*", message = "비밀번호에 숫자를 넣어 주세요")
@MaxUtf8Bytes(value = SignupRequest.PASSWORD_MAX_BYTES, message = "비밀번호가 너무 길어요")
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordRule {

  String message() default "비밀번호를 확인해 주세요";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
