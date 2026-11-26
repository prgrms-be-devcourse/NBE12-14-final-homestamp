package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.common.PasswordRule;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.MemberStatus;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 변경·재설정에서 회원 쪽 일(06 ACC-4a·5b). 토큰 폐기는 부르는 쪽(auth {@code PasswordService})이 같은 트랜잭션에서 한다.
 * 알림(문자·서비스 안 알림)은 보낼 길이 정해지면 더한다(#261).
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordService {

  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;

  /**
   * 현재 비밀번호를 확인하고 새 비밀번호로 바꾼다(06 ACC-4a). 해시 확인과 새 해시 만들기는 이 안에서 하고, 행 잠금은 마지막 {@code
   * flush}(UPDATE)에서만 잡는다. 이 잠금은 토큰을 주는 중인 로그인의 공유 잠금과 서로 기다린다({@code
   * MemberLoginService#confirmUnchanged}, #262). 같은 회원의 변경이 동시에 오면 {@code member.version}이 늦은 쪽을
   * {@code VERSION_CONFLICT}로 거절한다.
   *
   * @throws BusinessException {@code LOGIN_REQUIRED}(탈퇴했거나 없는 회원의 남은 토큰), {@code
   *     PASSWORD_MISMATCH}(현재 비밀번호가 틀림)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void change(UUID memberId, String currentPassword, String newPassword) {
    Member member =
        members
            .findById(memberId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    // BCrypt는 72바이트를 넘는 입력을 확인하지 못한다(예외). 가입에서 막으므로 넘으면 맞을 수 없다
    if (PasswordRule.Bytes.exceedsMax(currentPassword)
        || !member.canLogInWith(currentPassword, passwordEncoder)) {
      throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
    }
    member.changePasswordHash(passwordEncoder.encode(newPassword));
    members.flush();
  }

  /**
   * 이 번호를 인증한 회원의 비밀번호를 바꾸고 회원 ID를 돌려준다(06 ACC-5b). 그 번호의 회원이 없으면(가입하지 않은 번호, 탈퇴, 다른 계정이 번호를 가져감)
   * 아무것도 바꾸지 않고 비어 있다. 부르는 쪽이 인증 표를 쓰고 번호 잠금을 잡은 트랜잭션 안에서 부른다.
   *
   * @param phone 인증 표에서 읽은 정규화된 번호
   * @param passwordHash 부르는 쪽이 트랜잭션 전에 만든 해시
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> reset(String phone, String passwordHash) {
    Optional<Member> member =
        members.findByPhone(phone).filter(m -> m.getStatus() == MemberStatus.ACTIVE);
    member.ifPresent(m -> m.changePasswordHash(passwordHash));
    members.flush();
    return member.map(Member::getId);
  }
}
