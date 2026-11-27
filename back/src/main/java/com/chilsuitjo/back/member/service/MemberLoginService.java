package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.common.PasswordRule;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.LoginCheck;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인에서 회원 쪽 일(06 ACC-2). 이메일·비밀번호가 맞는 회원을 찾고, 토큰을 주기 전에 비밀번호가 그대로인지 다시 본다. 토큰은 부르는 쪽(auth {@code
 * LoginService})이 준다.
 *
 * <p>없는 이메일·틀린 비밀번호·탈퇴한 회원을 구분해 돌려주지 않는다(ACC-2d). 없는 이메일에도 해시 확인을 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class MemberLoginService {

  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;

  /** 없는 이메일일 때 비교할 해시. 처음 필요할 때 만든다(동시에 둘이 만들어도 해가 없다). */
  private volatile String dummyHash;

  /**
   * 이메일(대소문자 무시)과 비밀번호가 맞으면 확인 결과. 맞지 않으면 이유와 상관없이 비어 있다. 해시 확인(BCrypt)은 잠금 없이 이 읽기 전용 트랜잭션에서 끝낸다.
   *
   * @param email 받은 그대로의 이메일
   * @param rawPassword 받은 비밀번호
   */
  @Transactional(readOnly = true)
  public Optional<LoginCheck> authenticate(String email, String rawPassword) {
    // 가입에서 막으므로 넘으면 맞을 수 없다(BCrypt는 넘는 입력을 확인하지 못함)
    if (PasswordRule.Bytes.exceedsMax(rawPassword)) {
      return Optional.empty();
    }
    Optional<Member> member = members.findByEmailIgnoringCase(email);
    if (member.isEmpty()) {
      passwordEncoder.matches(rawPassword, dummyHash());
      return Optional.empty();
    }
    return member.flatMap(m -> m.checkLogin(rawPassword, passwordEncoder));
  }

  /**
   * {@link #authenticate} 뒤 비밀번호가 바뀌지 않았는지 보고, 토큰을 주는 동안 바뀌지 않게 회원 행을 공유 잠금한다(#262). 토큰을 만드는 트랜잭션
   * 안에서 토큰보다 먼저 부른다.
   *
   * <p>비밀번호 변경·재설정은 회원 행을 고친 뒤 토큰을 폐기하므로 둘 중 하나가 된다: 변경이 먼저 커밋되면 여기서 바뀐 해시를 보고 거절하고, 이 잠금이 먼저면 변경은
   * 로그인이 커밋될 때까지 기다렸다가 새로 생긴 토큰까지 폐기한다. 그래서 옛 비밀번호로 확인한 로그인이 폐기를 비켜 가지 않는다.
   *
   * @throws BusinessException {@code LOGIN_FAILED}(그사이 비밀번호가 바뀌었거나 탈퇴함, ACC-2d와 같은 답)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void confirmUnchanged(LoginCheck check) {
    boolean unchanged =
        members
            .lockPasswordHashForShare(check.memberId())
            .filter(check.credential()::equals)
            .isPresent();
    if (!unchanged) {
      throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }
  }

  private String dummyHash() {
    String hash = dummyHash;
    if (hash == null) {
      hash = passwordEncoder.encode(UUID.randomUUID().toString());
      dummyHash = hash;
    }
    return hash;
  }
}
