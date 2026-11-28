package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.member.service.MemberPhoneHmacService;
import com.chilsuitjo.back.notification.service.VerifiedPhoneOwners;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VerifiedPhoneOwners}의 auth 구현(#83 Q2·Q3, #462). 번호 잠금 키는 저장된 번호 HMAC의 앞 8바이트라 가입({@link
 * SignupService}의 {@code phoneLockKey})과 같은 잠금이다. 회원 여부는 member가 본다({@link
 * MemberPhoneHmacService}).
 *
 * <p>잠금 순서와 경합 규칙은 포트 Javadoc이 원본이다.
 */
@Service
@RequiredArgsConstructor
class VerifiedMemberPhoneLock implements VerifiedPhoneOwners {

  private final VerificationKeys keys;
  private final PhoneVerificationRepository verificationRows;
  private final MemberPhoneHmacService members;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockAndCheckVerifiedMember(String phoneHmac) {
    verificationRows.lockPhone(keys.phoneLockKeyOfHmac(phoneHmac));
    return members.isVerifiedActive(phoneHmac);
  }
}
