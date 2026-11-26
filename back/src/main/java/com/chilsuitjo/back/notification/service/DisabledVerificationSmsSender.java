package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 문자 발송이 꺼진 상태({@code app.sms.mode=disabled})의 인증 문자 발송기. 보내지 않고 {@code FAILED}를 돌려준다. auth는 그 인증
 * 행을 지우고 {@code SMS_SEND_FAILED}(503)로 답한다(06 ACC-1j). 그래서 이 상태로는 가입·재설정 인증을 마칠 수 없다(실제 사용자 오픈 금지).
 *
 * <p>번호·인증번호는 기억하지도 로그에 남기지도 않는다.
 */
@Service
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "disabled")
public class DisabledVerificationSmsSender implements VerificationSmsSender {

  private static final Logger log = LoggerFactory.getLogger(DisabledVerificationSmsSender.class);

  @Override
  public VerificationSendResult send(String phone, String code) {
    log.warn("문자 발송이 꺼져 있어(app.sms.mode=disabled) 인증 문자를 보내지 않음");
    return VerificationSendResult.FAILED;
  }
}
