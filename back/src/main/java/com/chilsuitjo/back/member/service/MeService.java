package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.dto.MeResponse;
import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.member.rule.MemberStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 로그인한 회원 본인의 정보(06 4-1 {@code /api/me}, ACC-4·6). */
@Service
@RequiredArgsConstructor
public class MeService {

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
    return members
        .findById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .map(MeResponse::from)
        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
  }
}
