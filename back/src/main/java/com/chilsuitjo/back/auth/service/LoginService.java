package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.dto.LoginRequest;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.global.security.ConditionalOnLoginTokenKeys;
import com.chilsuitjo.back.member.dto.LoginCheck;
import com.chilsuitjo.back.member.service.MemberLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이메일·비밀번호 로그인(06 ACC-2, 4-1 {@code POST /api/auth/login}). 회원 확인은 member가, 토큰 발급은 {@link
 * AuthTokenService#issue}가 한다. 로그인할 때마다 새 묶음(기기 하나)이 생긴다.
 *
 * <p>해시 확인은 member의 읽기 전용 트랜잭션에서 잠금 없이 끝내고, 토큰을 주는 쓰기 트랜잭션에서 비밀번호가 그대로인지 회원 행을 공유 잠금해 다시 본다({@link
 * MemberLoginService#confirmUnchanged}). 확인과 발급 사이에 비밀번호가 바뀌면 옛 비밀번호로 새 기기가 생기지 않는다(#262). 요청 횟수
 * 제한({@code RATE_LIMITED})은 nginx {@code limit_req}가 맡는다(04 3-2 "요청 제한").
 */
@Service
@ConditionalOnLoginTokenKeys
@RequiredArgsConstructor
public class LoginService {

  private final MemberLoginService members;
  private final AuthTokenService tokens;
  private final TransactionTemplate transaction;

  /**
   * 맞는 회원이면 새 묶음의 토큰을 준다(ACC-2a).
   *
   * @throws BusinessException {@code LOGIN_FAILED}(없는 이메일·틀린 비밀번호·탈퇴한 회원, 확인 뒤 비밀번호가 바뀜 모두 같음,
   *     ACC-2d)
   */
  public IssuedTokens login(LoginRequest request) {
    LoginCheck check =
        members
            .authenticate(request.email(), request.password())
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
    return issue(check);
  }

  /**
   * 로그인의 둘째 단계: 비밀번호가 확인한 그대로인지 본 뒤 토큰을 준다. {@link #login}이 부르고, 테스트가 두 단계 사이에 비밀번호 변경을 끼워 볼 때도
   * 부른다.
   *
   * @throws BusinessException {@code LOGIN_FAILED}(확인 뒤 비밀번호가 바뀌었거나 탈퇴함)
   */
  public IssuedTokens issue(LoginCheck check) {
    return transaction.execute(status -> {
      members.confirmUnchanged(check);
      return tokens.issue(check.memberId());
    });
  }
}
