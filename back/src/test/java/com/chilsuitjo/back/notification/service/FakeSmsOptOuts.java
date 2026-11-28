package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PhoneNumber;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * DB 없는 테스트용 {@link SmsOptOuts}. 실제 구현과 같게 번호를 {@code PhoneNumber.normalize}로 맞춰 비교하고, 정규화할 수 없는
 * 번호는 {@code VALIDATION_FAILED}를 올린다(빈 결과로 바꾸지 않음). HMAC·DB는 흉내 내지 않는다(그건 {@code
 * JpaSmsOptOutsTest}가 본다).
 *
 * <p>쓰는 법: {@link #optOut(String)}으로 거부한 번호를 넣고 규칙·서비스 단위 테스트에 {@code new}로 넣는다. 스프링 테스트는 실제 구현을
 * 쓴다.
 */
public class FakeSmsOptOuts implements SmsOptOuts {

  private final Set<String> optedOut = new HashSet<>();

  /** 테스트 준비: 이 번호를 수신 거부 목록에 넣는다. */
  public FakeSmsOptOuts optOut(String phone) {
    optedOut.add(PhoneNumber.normalize(phone));
    return this;
  }

  @Override
  public boolean isOptedOut(String phone) {
    return optedOut.contains(PhoneNumber.normalize(phone));
  }

  /** 등록. 실제 구현처럼 넣었을 때만 {@code true}다. 시각과 트랜잭션은 흉내 내지 않는다. */
  @Override
  public boolean optOut(String phone, Instant at) {
    return optedOut.add(PhoneNumber.normalize(phone));
  }

  /** 해제. 지웠을 때만 {@code true}다. 감사·아웃박스는 흉내 내지 않는다(그건 {@code JpaSmsOptOutsTest}가 본다). */
  @Override
  public boolean releaseForVerifiedOwner(String phone, Instant at) {
    return optedOut.remove(PhoneNumber.normalize(phone));
  }
}
