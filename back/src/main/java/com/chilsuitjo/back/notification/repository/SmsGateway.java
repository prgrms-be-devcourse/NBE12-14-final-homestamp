package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;

/**
 * 문자 업체 호출 부품(04 4-2 "외부 호출 부품은 {@code notification/repository/}"). 실제 솔라피 구현은 NOTI-11(3주차), 그 전에는
 * {@link FakeSmsGateway}다.
 *
 * <p>번호·본문을 로그·예외 문구에 남기지 않는다. 시간 제한을 넘기면 {@code UNKNOWN}으로 돌려준다(보낸 것으로 봄).
 */
public interface SmsGateway {

  SmsSendResult send(SmsMessage message);
}
