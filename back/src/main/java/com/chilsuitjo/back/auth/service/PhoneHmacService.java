package com.chilsuitjo.back.auth.service;

import com.chilsuitjo.back.global.common.PhoneNumber;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link PhoneHmacs}의 auth 구현(#163 1-3, decisions 2026-11-26 "번호 HMAC"). 번호를 {@link
 * PhoneNumber#normalize}로 정규화하고 {@link VerificationKeys}의 번호 HMAC 키({@code app.keys.phone-hmac})로
 * 계산한다. 키 검사(없거나 다른 키와 같음)는 {@link VerificationKeys}가 앱 시작에서 한다.
 *
 * <p>정규화할 수 없는 번호는 {@code PhoneNumber}의 {@code BusinessException(VALIDATION_FAILED)}를 그대로 올린다. 예외
 * 문구는 오류 코드 이름뿐이라 번호가 남지 않고, 이 클래스도 번호와 값을 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
class PhoneHmacService implements PhoneHmacs {

  private final VerificationKeys keys;

  @Override
  public String phoneHmac(String phone) {
    return keys.phoneHmac(PhoneNumber.normalize(phone));
  }
}
