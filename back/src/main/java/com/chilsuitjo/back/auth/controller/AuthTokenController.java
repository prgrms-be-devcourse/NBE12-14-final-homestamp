package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.AccessTokenResponse;
import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.service.AuthTokenService;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.global.security.LoginMember;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재발급·로그아웃 API(06 4-1, ACC-2). 리프레시 토큰은 HttpOnly·Secure·SameSite=Strict 쿠키({@code
 * Path=/api/auth/refresh})로만 주고받는다(04 4-1 브라우저 보관).
 *
 * <p>재발급은 쿠키로 인증하므로 Origin이 화면의 Origin({@code app.origin})과 같을 때만 받는다(04 4-1 재발급 요청 보호). 쿠키가 없거나
 * Origin이 다르면 {@code LOGIN_REQUIRED}다.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnLoginTokenKeys
public class AuthTokenController {

  static final String REFRESH_COOKIE = "refresh_token";
  static final String REFRESH_PATH = "/api/auth/refresh";
  private static final String BEARER = "Bearer ";

  private final AuthTokenService service;
  private final AccessTokens accessTokens;
  private final String origin;

  AuthTokenController(
      AuthTokenService service, AccessTokens accessTokens, @Value("${app.origin:}") String origin) {
    this.service = service;
    this.accessTokens = accessTokens;
    this.origin = requireOrigin(origin);
  }

  /**
   * 브라우저가 보내는 Origin 모양({@code http(s)://host[:port]})만 받는다. 끝의 {@code /}나 경로가 붙으면 모든 재발급이 소리 없이
   * {@code LOGIN_REQUIRED}가 되므로 시작할 때 거절한다.
   */
  static String requireOrigin(String origin) {
    if (origin == null || origin.isBlank()) {
      throw new IllegalStateException("app.origin이 없다. 재발급 요청의 Origin을 확인하려면 화면의 Origin을 넣어야 시작한다");
    }
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException e) {
      throw badOrigin(origin);
    }
    boolean web = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
    if (!web
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || !uri.getRawPath().isEmpty()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw badOrigin(origin);
    }
    return origin;
  }

  private static IllegalStateException badOrigin(String origin) {
    return new IllegalStateException(
        "app.origin은 http(s)://host[:port] 모양이어야 한다(끝의 /나 경로 없이): " + origin);
  }

  /** 새 액세스 토큰(본문)과 다음 리프레시 토큰(쿠키). 오류는 {@code LOGIN_REQUIRED}·{@code REFRESH_REUSED}(401). */
  @PostMapping("/refresh")
  public ResponseEntity<AccessTokenResponse> refresh(
      @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String requestOrigin,
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    if (!origin.equals(requestOrigin)) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
    }
    IssuedTokens issued = service.refresh(refreshToken);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            refreshCookie(issued.refreshToken(), issued.refreshMaxAge()).toString())
        .body(new AccessTokenResponse(issued.accessToken()));
  }

  /** 그 기기의 리프레시 토큰 묶음을 폐기하고 쿠키를 지운다. 묶음은 액세스 토큰에 담긴 것을 쓴다(쿠키는 이 주소로 오지 않음). 이미 로그아웃했어도 204다. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @LoginMember UUID memberId, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
    String token = authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()) : "";
    accessTokens
        .read(token.strip())
        .filter(claims -> claims.memberId().equals(memberId))
        .ifPresent(claims -> service.logout(memberId, claims.familyId()));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
        .build();
  }

  /** 리프레시 쿠키. 지울 때도 같은 이름·Path·속성으로 수명 0을 보낸다. */
  static ResponseCookie refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(REFRESH_PATH)
        .maxAge(maxAge)
        .build();
  }
}
