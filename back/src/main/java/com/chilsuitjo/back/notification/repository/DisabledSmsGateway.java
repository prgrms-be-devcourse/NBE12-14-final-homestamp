package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 문자 발송이 꺼진 상태({@code app.sms.mode=disabled}, 실제 연동 전 prod)의 발송기. 업체를 부르지 않고 {@code REJECTED}(최종
 * 실패)를 돌려준다.
 *
 * <p>{@code RETRY}로 두지 않는 이유: 꺼진 동안의 문자가 쌓였다가 연동을 켜는 순간 오래된 통지가 한꺼번에 나간다. 성공({@code SENT})도 돌려주지
 * 않는다(보내지 않은 문자를 보낸 것으로 남기지 않음). 번호·본문은 로그에 남기지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.sms.mode", havingValue = "disabled")
public class DisabledSmsGateway implements SmsGateway {

  private static final Logger log = LoggerFactory.getLogger(DisabledSmsGateway.class);

  @Override
  public SmsSendResult send(SmsMessage message) {
    log.warn("문자 발송이 꺼져 있어(app.sms.mode=disabled) 보내지 않음: batch={}", message.batchLeadId());
    return SmsSendResult.rejected();
  }
}
