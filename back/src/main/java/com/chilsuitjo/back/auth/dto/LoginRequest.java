package com.chilsuitjo.back.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청(06 4-1 {@code POST /api/auth/login}). 06에 칸 이름이 없어 가입 요청({@code SignupRequest})과 같은 이름을
 * 쓴다.
 *
 * <p>비밀번호 규칙({@code password.rule})은 여기서 보지 않는다. 규칙에 맞지 않는 비밀번호도 {@code VALIDATION_FAILED}가 아니라
 * {@code LOGIN_FAILED}로 돌려준다(ACC-2d). 빈 칸만 {@code VALIDATION_FAILED}다. 이메일 상한은 DB 칸 크기(320)다.
 */
public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotEmpty String password) {

  /** 비밀번호가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "LoginRequest[email=" + email + "]";
  }
}
