package com.chilsuitjo.back.member.service;

import com.chilsuitjo.back.member.repository.MemberRepository;
import com.chilsuitjo.back.notification.service.ActiveRecipients;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link ActiveRecipients}의 member 구현(#424). 잠금 순서와 경합 규칙은 포트 Javadoc이 원본이다. */
@Service
@RequiredArgsConstructor
class MemberActiveRecipients implements ActiveRecipients {

  private final MemberRepository members;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean holdActive(UUID memberId) {
    return members.lockActiveForShare(Objects.requireNonNull(memberId, "memberId")).isPresent();
  }
}
