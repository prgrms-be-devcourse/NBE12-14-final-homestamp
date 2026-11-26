package com.chilsuitjo.back.auth.dto;

import com.chilsuitjo.back.member.dto.PasswordRule;
import jakarta.validation.constraints.NotNull;

/**
 * 비밀번호 변경({@code POST /api/me/password}, 06 4-1, ACC-4a). 칸 이름은 06 4-1과 같다(바꾸면 06도 함께).
 *
 * @param currentPassword 지금 비밀번호. 규칙은 보지 않고 맞는지만 본다(틀리면 {@code PASSWORD_MISMATCH})
 * @param newPassword 새 비밀번호({@link PasswordRule})
 */
public record PasswordChangeRequest(
    @NotNull String currentPassword, @NotNull @PasswordRule String newPassword) {

  /** 비밀번호가 로그에 남지 않게 한다. */
  @Override
  public String toString() {
    return "PasswordChangeRequest[***]";
  }
}
