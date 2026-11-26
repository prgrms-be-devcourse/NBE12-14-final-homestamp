package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.PasswordChangeRequest;
import com.chilsuitjo.back.auth.dto.PasswordResetRequest;
import com.chilsuitjo.back.auth.service.PasswordService;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.AccessTokens;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.global.security.LoginMember;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 변경·재설정 API(06 4-1, ACC-4a·5b). 변경은 로그인한 회원이, 재설정은 로그인 없이 RESET 인증 표로 부른다({@code
 * SecurityConfig} 공개 POST). 둘 다 성공하면 204다.
 */
@RestController
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class PasswordController {

  private static final String BEARER = "Bearer ";

  private final PasswordService passwords;
  private final AccessTokens accessTokens;

  /**
   * 비밀번호를 바꾸고 지금 기기를 뺀 다른 기기를 로그아웃시킨다. 지금 기기는 요청한 액세스 토큰의 묶음이다(로그아웃과 같은 방법).
   *
   * <p>{@code PASSWORD_MISMATCH}(400), {@code VALIDATION_FAILED}(400), {@code LOGIN_REQUIRED}(401)
   */
  @PostMapping("/api/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void change(
      @LoginMember UUID memberId,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
      @Valid @RequestBody PasswordChangeRequest request) {
    String token = authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()) : "";
    UUID familyId =
        accessTokens
            .read(token.strip())
            .filter(claims -> claims.memberId().equals(memberId))
            .map(AccessTokens.Claims::familyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    passwords.change(memberId, familyId, request);
  }

  /**
   * RESET 인증 표로 비밀번호를 다시 정하고 모든 기기를 로그아웃시킨다. {@code CODE_EXPIRED}(400), {@code
   * VALIDATION_FAILED}(400)
   */
  @PostMapping("/api/auth/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody PasswordResetRequest request) {
    passwords.reset(request);
  }
}
