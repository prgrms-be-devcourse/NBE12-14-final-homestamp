package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.dto.LoginRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.member.service.MemberLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 이메일·비밀번호 로그인(06 ACC-2, 4-1 {@code POST /api/auth/login}). 회원 확인은 member가, 토큰 발급은 {@link
 * AuthTokenService#issue}가 한다. 로그인할 때마다 새 묶음(기기 하나)이 생긴다.
 *
 * <p>해시 확인은 member의 읽기 전용 트랜잭션에서 끝내고, 토큰 발급만 쓰기 트랜잭션이다. 요청 횟수 제한({@code RATE_LIMITED})은 nginx
 * {@code limit_req}가 맡는다(04 3-2 "요청 제한").
 */
@Service
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class LoginService {

  private final MemberLoginService members;
  private final AuthTokenService tokens;

  /**
   * 맞는 회원이면 새 묶음의 토큰을 준다(ACC-2a).
   *
   * @throws BusinessException {@code LOGIN_FAILED}(없는 이메일·틀린 비밀번호·탈퇴한 회원 모두 같음, ACC-2d)
   */
  public IssuedTokens login(LoginRequest request) {
    return members
        .authenticate(request.email(), request.password())
        .map(tokens::issue)
        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
  }
}
