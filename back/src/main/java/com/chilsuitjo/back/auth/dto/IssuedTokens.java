package com.chilsuitjo.back.auth.dto;

import java.time.Duration;
import java.util.Objects;

/**
 * 로그인·재발급으로 내준 토큰. 액세스 토큰은 응답 본문으로, 리프레시 토큰은 쿠키로 보낸다(06 4-0 인증).
 *
 * @param refreshMaxAge 리프레시 쿠키의 남은 수명(그 토큰 행의 만료까지)
 */
public record IssuedTokens(String accessToken, String refreshToken, Duration refreshMaxAge) {

  public IssuedTokens {
    Objects.requireNonNull(accessToken);
    Objects.requireNonNull(refreshToken);
    Objects.requireNonNull(refreshMaxAge);
  }

  /** 로그에 토큰이 남지 않게 값을 가린다(06 P-7 개인정보: 로그에 토큰 없음). */
  @Override
  public String toString() {
    return "IssuedTokens[accessToken=***, refreshToken=***, refreshMaxAge=" + refreshMaxAge + "]";
  }
}
