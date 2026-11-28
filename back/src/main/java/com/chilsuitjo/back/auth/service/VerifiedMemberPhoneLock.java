package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.auth.repository.PhoneVerificationRepository;
import com.chilsuitjo.back.member.service.MemberPhoneHmacService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 번호 잠금을 잡고 그 번호가 인증된 활동 회원의 번호인지 본다(#83 Q2, #462). 링크 그만 받기가 비회원으로 본 뒤 그 번호의 가입이 끝나 인증한 번호가 수신 거부
 * 목록에 남는 일을 막는다: 가입({@link SignupService})과 같은 번호 잠금이라, 가입이 먼저면 회원으로 보고 이쪽이 먼저면 가입이 거부 행을 지운다.
 *
 * <p>링크 쪽이 부를 포트(위치·이름)는 #83 Q3 답을 받은 뒤 연결한다. 그때 이 클래스가 그 포트를 구현한다.
 *
 * <p>잠금 순서: 부르는 쪽은 호실(pipeline) → 링크 → 이 번호 잠금 순으로 잡는다. 가입·번호 변경은 번호 잠금 뒤에 호실을 잠그지 않으므로 교착이 없다.
 */
@Service
@RequiredArgsConstructor
public class VerifiedMemberPhoneLock {

  private final VerificationKeys keys;
  private final PhoneVerificationRepository verificationRows;
  private final MemberPhoneHmacService members;

  /**
   * 부르는 쪽 트랜잭션에서 번호 잠금을 잡고(트랜잭션이 끝날 때 풀림) 그 번호 HMAC의 인증된 활동 회원이 있으면 {@code true}.
   *
   * @param phoneHmac {@code PhoneHmacs} 값(소문자 hex 64자)
   * @throws IllegalArgumentException 소문자 hex 64자가 아님
   * @throws IllegalStateException 번호 HMAC을 아직 채우지 않은 회원이 있음(비회원으로 보지 않음)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockAndCheckVerifiedMember(String phoneHmac) {
    verificationRows.lockPhone(keys.phoneLockKeyOfHmac(phoneHmac));
    return members.isVerifiedActive(phoneHmac);
  }
}
