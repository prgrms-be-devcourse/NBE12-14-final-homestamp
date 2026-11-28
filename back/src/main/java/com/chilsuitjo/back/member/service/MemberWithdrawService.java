package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.common.PasswordRule;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.LoginCheck;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.MemberStatus;
import java.time.Instant;
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
 * 탈퇴에서 회원 쪽 일(06 KEEP-4c·4f, 03 L "탈퇴"). 토큰 폐기와 호실 자리는 부르는 쪽(auth {@code WithdrawService})이 같은
 * 트랜잭션에서 한다. 신원 칸 예외라 행동 기록을 남기지 않고, 커밋된 뒤에만 내부 기록 한 줄을 남긴다(05 2-3 "계정(호실 밖)", 비밀번호 변경과 같은 방식).
 *
 * <p>비밀번호 확인({@link #check})은 잠금 없이 먼저 하고, 가림({@link #withdraw})은 호실을 모두 잠근 뒤 회원 행을 잠그고 한다(잠금 순서 호실
 * → 회원 행, #424). 해시 확인(BCrypt)은 느려서 호실 잠금을 잡은 채 하지 않는다. 로그인({@code MemberLoginService})과 같은 두 단계다.
 */
@Service
@RequiredArgsConstructor
public class MemberWithdrawService {

  private static final Logger log = LoggerFactory.getLogger(MemberWithdrawService.class);

  private final MemberRepository members;
  private final PasswordEncoder passwordEncoder;

  /**
   * 다시 넣은 비밀번호를 확인하고, 확인한 해시를 담아 돌려준다. 잠그지 않는다.
   *
   * @param password 다시 넣은 비밀번호. 없거나(null·빈 값) 틀리면 {@code PASSWORD_MISMATCH}(06 KEEP-4c)
   * @throws BusinessException {@code LOGIN_REQUIRED}(탈퇴했거나 없는 회원의 남은 토큰), {@code PASSWORD_MISMATCH}
   */
  @Transactional(readOnly = true)
  public LoginCheck check(UUID memberId, String password) {
    Member member =
        members
            .findById(memberId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    // BCrypt는 72바이트를 넘는 입력을 확인하지 못한다(예외). 가입에서 막으므로 넘으면 맞을 수 없다
    if (password == null || password.isEmpty() || PasswordRule.Bytes.exceedsMax(password)) {
      throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
    }
    return member
        .checkLogin(password, passwordEncoder)
        .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_MISMATCH));
  }

  /**
   * 회원 행을 쓰기 잠금하고({@code lockById}, 확인 기록 삭제 함수와 같은 잠금) 회원을 가린 뒤 확인 기록을 지운다. 같은 회원의 비밀번호 변경·내 정보
   * 수정·약관 다시 확인·로그인 토큰 발급·연결({@link MemberQueryService#holdActive})과 한 줄로 선다.
   *
   * <p>{@link #check} 뒤 비밀번호가 바뀌었으면(해시가 다름) 확인한 비밀번호가 지금 비밀번호가 아니라서 {@code PASSWORD_MISMATCH}다. 순서:
   * 잠금 → 해시 비교 → 가림 {@code flush} → {@code record.purge_member_acknowledgements}(함수가 {@code
   * WITHDRAWN}을 확인하므로 가림이 먼저). 확인 기록 삭제나 삭제 기록이 실패하면 예외가 올라가 탈퇴 전체가 되돌아간다(KEEP-4f).
   *
   * @param checked {@link #check} 결과
   * @param at 탈퇴 시각({@code withdrawn_at}과 삭제 기록 시각)
   * @return 지운 확인 기록 수
   * @throws BusinessException {@code LOGIN_REQUIRED}(그사이 탈퇴함), {@code PASSWORD_MISMATCH}(그사이 비밀번호가
   *     바뀜)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int withdraw(LoginCheck checked, Instant at) {
    UUID memberId = checked.memberId();
    Member member =
        members
            .lockById(memberId)
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    if (!member.hasPasswordHash(checked.credential())) {
      throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
    }
    member.withdraw(at);
    members.flush();
    int purged = members.purgeAcknowledgements(memberId, at);
    logAfterCommit(memberId);
    return purged;
  }

  /** 회원 ID(앞 8자리)와 탈퇴 사실만 남긴다. 이메일·이름·번호는 남기지 않는다(04 "개인정보 로그"). 롤백되면 남기지 않는다. */
  private static void logAfterCommit(UUID memberId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.info("탈퇴: 회원 {}", memberId.toString().substring(0, 8));
          }
        });
  }
}
