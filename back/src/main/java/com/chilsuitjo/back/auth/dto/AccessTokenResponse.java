package com.chilsuitjo.back.auth.dto;

/** 로그인·재발급 응답 본문. 리프레시 토큰은 본문에 넣지 않고 HttpOnly 쿠키로만 보낸다(04 4-1 브라우저 보관). */
public record AccessTokenResponse(String accessToken) {

  @Override
  public String toString() {
    return "AccessTokenResponse[accessToken=***]";
  }
}
