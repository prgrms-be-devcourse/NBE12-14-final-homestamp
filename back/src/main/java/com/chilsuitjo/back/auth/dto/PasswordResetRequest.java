package com.chilsuitjo.back.auth.dto;

import com.chilsuitjo.back.global.common.PasswordRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 비밀번호 재설정({@code POST /api/auth/reset-password}, 06 4-1, ACC-5b). 칸 이름은 06 4-1과 같다(바꾸면 06도 함께).
 *
 * @param verificationTicket RESET 휴대폰 인증 표(가입과 같은 칸 이름). 확인과 소비는 인증 쪽이 맡는다
 * @param newPassword 새 비밀번호({@link PasswordRule})
 */
public record PasswordResetRequest(
    @NotBlank String verificationTicket, @NotNull @PasswordRule String newPassword) {

  /** 비밀번호와 인증 표가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "PasswordResetRequest[***]";
  }
}
