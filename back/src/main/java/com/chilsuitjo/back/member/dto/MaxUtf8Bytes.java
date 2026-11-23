package com.chilsuitjo.back.member.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * 글자를 UTF-8로 바꾼 길이가 {@link #value()}바이트 이하인지 본다. 비밀번호는 BCrypt가 72바이트까지만 받아서 글자 수가 아니라 바이트로 막는다(06
 * 0-1 {@code password.rule}). null은 통과한다(필수 여부는 {@code NotNull}이 본다).
 */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

  int value();

  String message() default "너무 길어요";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int max;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
      this.max = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
  }
}
