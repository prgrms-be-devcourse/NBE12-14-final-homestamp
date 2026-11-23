package com.chilsuitjo.back.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청(06 4-1 {@code POST /api/auth/login}). 06에 칸 이름이 없어 가입 요청({@code SignupRequest})과 같은 이름을
 * 쓴다.
 *
 * <p>비밀번호 규칙({@code password.rule})은 여기서 보지 않는다. 규칙에 맞지 않는 비밀번호도 {@code VALIDATION_FAILED}가 아니라
 * {@code LOGIN_FAILED}로 돌려준다(ACC-2d). 빈 칸과 이메일 모양이 아닌 값만 {@code VALIDATION_FAILED}다.
 *
 * <p>이메일은 가입과 같은 검사({@code @Email}, 상한은 DB 칸 크기 320)를 한다. 가입한 이메일은 모두 통과하므로 가입 여부가 드러나지 않고, NUL처럼
 * PostgreSQL 문자열에 넣을 수 없는 값이 조회까지 가서 500이 되지 않는다.
 */
public record LoginRequest(
    @NotBlank @Email @Size(max = 320) String email, @NotEmpty String password) {

  /** 비밀번호가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "LoginRequest[email=" + email + "]";
  }
}
