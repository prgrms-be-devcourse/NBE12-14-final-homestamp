package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.SmsOptOutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link SmsOptOuts}의 운영 구현. 번호를 {@link PhoneHmacs}로 바꿔 {@code state.sms_opt_out}에 그 행이 있는지 본다.
 * HMAC·DB 실패는 잡지 않고 그대로 올린다(판정 실패를 "거부 없음"으로 바꾸지 않음). 번호와 HMAC 값은 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
class JpaSmsOptOuts implements SmsOptOuts {

  private final PhoneHmacs phoneHmacs;
  private final SmsOptOutRepository optOuts;

  @Override
  public boolean isOptedOut(String phone) {
    return optOuts.existsById(phoneHmacs.phoneHmac(phone));
  }
}
