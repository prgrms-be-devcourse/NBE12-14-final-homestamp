package com.chilsuitjo.back.auth.controller;

import com.chilsuitjo.back.auth.dto.WithdrawRequest;
import com.chilsuitjo.back.auth.service.WithdrawService;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.global.security.LoginMember;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 탈퇴 API(06 4-1 {@code POST /api/me/withdraw}, KEEP-4). 회원·토큰·호실 자리를 함께 바꿔야 해서 비밀번호 변경처럼 auth에
 * 둔다({@code member → room}은 {@code room → member}와 고리가 생김). 바뀐 약관을 확인하기 전에도 받는다({@code
 * TermsGateInterceptor}).
 */
@RestController
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class WithdrawController {

  private final WithdrawService withdrawals;

  /**
   * 비밀번호를 다시 넣어 탈퇴한다. 204. 모든 기기의 리프레시 토큰을 폐기한다.
   *
   * <p>{@code PASSWORD_MISMATCH}(400, 본문이나 비밀번호가 없어도), {@code LOGIN_REQUIRED}(401), {@code
   * VERSION_CONFLICT}(409, 같은 회원의 자리가 동시에 바뀜)
   */
  @PostMapping("/api/me/withdraw")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdraw(
      @LoginMember UUID memberId, @RequestBody(required = false) WithdrawRequest request) {
    withdrawals.withdraw(memberId, request == null ? new WithdrawRequest(null) : request);
  }
}
