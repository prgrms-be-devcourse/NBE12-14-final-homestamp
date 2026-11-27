package com.chilsuitjo.back.member.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 로그인에서 비밀번호 확인을 통과한 결과(06 ACC-2). 토큰을 주기 전에 {@code MemberLoginService.confirmUnchanged}로 그사이 비밀번호가
 * 바뀌지 않았는지 다시 본다(비밀번호 변경·재설정의 토큰 폐기를 로그인이 비켜 가지 않게, #262).
 *
 * <p>{@code credential}은 확인한 비밀번호 해시다. 비교에만 쓰고 응답·로그에 내보내지 않는다({@link #toString}은 회원 ID만 보여 준다).
 */
public record LoginCheck(UUID memberId, String credential) {

  public LoginCheck {
    Objects.requireNonNull(memberId, "memberId");
    Objects.requireNonNull(credential, "credential");
  }

  @Override
  public String toString() {
    return "LoginCheck[memberId=" + memberId + "]";
  }
}
