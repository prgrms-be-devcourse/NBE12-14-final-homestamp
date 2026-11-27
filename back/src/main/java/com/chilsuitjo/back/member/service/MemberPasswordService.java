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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 비밀번호 변경·재설정에서 회원 쪽 일(06 ACC-4a·5b). 토큰 폐기는 부르는 쪽(auth {@code PasswordService})이 같은 트랜잭션에서 한다. 신원
 * 칸이라 행동 기록을 남기지 않고, 바뀌었으면 커밋 뒤 내부 기록만 남긴다(05 0장·2-3). 알림(문자·서비스 안 알림)은 보낼 길이 정해지면 더한다(#261).
 */
@Service
@RequiredArgsConstructor
public class MemberPasswordService {

  private static final Logger log = LoggerFactory.getLogger(MemberPasswordService.class);

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
    logAfterCommit("비밀번호 변경", memberId);
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
    member.ifPresent(m -> logAfterCommit("비밀번호 재설정", m.getId()));
    return member.map(Member::getId);
  }

  /**
   * 비밀번호 변경·재설정 내부 기록(05 2-3 "계정(호실 밖)"): 토큰 폐기까지 함께 커밋된 뒤에만 회원 ID(앞 8자리)와 바꾼 사실을 앱 로그에 남긴다.
   * 비밀번호·해시·번호는 남기지 않는다(05 0장 신원 예외, 04 "개인정보 로그"). 롤백되면 남기지 않는다. 이름 변경({@code MeService})과 같은
   * 방식이다.
   */
  private static void logAfterCommit(String what, UUID memberId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            // ID는 앞 8자리만(04 11장 "로그")
            log.info("{}: 회원 {}", what, memberId.toString().substring(0, 8));
          }
        });
  }
}
