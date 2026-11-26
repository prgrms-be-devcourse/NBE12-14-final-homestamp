package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.MeResponse;
import com.chilsuitjo.back.member.dto.MeUpdateRequest;
import com.chilsuitjo.back.member.entity.Member;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.MemberStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 로그인한 회원 본인의 정보(06 4-1 {@code /api/me}, ACC-4·6). */
@Service
@RequiredArgsConstructor
public class MeService {

  private static final Logger log = LoggerFactory.getLogger(MeService.class);

  private final MemberRepository members;

  /**
   * 내 정보.
   *
   * <p>액세스 토큰은 만료 전까지 회원을 다시 보지 않으므로, 탈퇴했거나 없는 회원의 토큰이 여기까지 올 수 있다. 그때는 다시 로그인하게 {@code
   * LOGIN_REQUIRED}로 답한다(가입 여부를 따로 알리지 않는다).
   *
   * @throws BusinessException {@code LOGIN_REQUIRED}
   */
  @Transactional(readOnly = true)
  public MeResponse me(UUID memberId) {
    return MeResponse.from(activeMember(memberId));
  }

  /**
   * 이름·문자 설정을 바꾸고 바뀐 내 정보를 돌려준다(06 ACC-4·6, NOTI-8b). 보낸 칸만 바꾼다. 신원 칸이라 행동 기록을 남기지 않고, 이름이 실제로
   * 바뀌었으면 커밋 뒤 내부 기록만 남긴다(05 0장·2-3).
   *
   * <p>같은 회원의 수정이 동시에 오면 {@code member.version}이 늦은 쪽을 {@code VERSION_CONFLICT}로 거절한다.
   *
   * @throws BusinessException {@code LOGIN_REQUIRED}(탈퇴했거나 없는 회원의 남은 토큰)
   */
  @Transactional
  public MeResponse update(UUID memberId, MeUpdateRequest request) {
    Member member = activeMember(memberId);
    if (request.displayName() != null && !request.displayName().equals(member.getDisplayName())) {
      member.changeDisplayName(request.displayName());
      logNameChangeAfterCommit(memberId);
    }
    if (request.smsEnabled() != null) {
      member.changeSmsEnabled(request.smsEnabled());
    }
    // 응답 내용이 커밋될 내용과 같도록, 버전 충돌은 응답을 만들기 전에 드러낸다
    members.flush();
    return MeResponse.from(member);
  }

  /**
   * 이름 변경 내부 기록(05 2-3 "계정(호실 밖)"): 변경이 커밋된 뒤에만 회원 ID와 변경 사실을 앱 로그에 남긴다. 이름 값은 남기지 않는다(05 0장 신원 예외,
   * 04 "개인정보 로그"). 롤백되거나 같은 이름이면 남기지 않는다.
   */
  private static void logNameChangeAfterCommit(UUID memberId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.info("이름 변경: 회원 {}", memberId);
          }
        });
  }

  private Member activeMember(UUID memberId) {
    return members
        .findById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
  }
}
