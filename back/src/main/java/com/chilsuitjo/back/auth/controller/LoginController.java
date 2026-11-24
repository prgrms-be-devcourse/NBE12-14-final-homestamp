package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.AccessTokenResponse;
import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.dto.LoginRequest;
import com.chilsuitjo.back.auth.service.LoginService;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 API(06 4-1, ACC-2). 로그인 없이 부른다({@code SecurityConfig} 공개 POST). 액세스 토큰은 본문, 리프레시 토큰은 재발급과 같은
 * 쿠키({@link AuthTokenController#refreshCookie})로 준다(04 4-1 브라우저 보관).
 */
@RestController
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class LoginController {

  private final LoginService logins;

  /**
   * 200과 액세스 토큰(본문)·리프레시 토큰(쿠키). 오류는 {@code LOGIN_FAILED}(401), 빈 칸은 {@code
   * VALIDATION_FAILED}(400).
   */
  @PostMapping("/api/auth/login")
  public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
    IssuedTokens issued = logins.login(request);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            AuthTokenController.refreshCookie(issued.refreshToken(), issued.refreshMaxAge())
                .toString())
        .body(new AccessTokenResponse(issued.accessToken()));
  }
}
