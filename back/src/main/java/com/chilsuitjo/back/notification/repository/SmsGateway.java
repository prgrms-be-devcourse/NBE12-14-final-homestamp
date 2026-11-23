package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.dto.SmsMessage;
import com.chilsuitjo.back.notification.dto.SmsSendResult;

/**
 * 문자 업체 호출 부품(04 4-2 "외부 호출 부품은 {@code notification/repository/}"). 실제 솔라피 구현은 NOTI-11(3주차), 그 전에는
 * {@link FakeSmsGateway}다.
 *
 * <p>번호·본문을 로그·예외 문구에 남기지 않는다. 결과는 예외가 아니라 값으로 돌려준다: 연결 거부처럼 업체에 닿지 않은 게 확실하면 {@code RETRY}, 시간
 * 제한처럼 닿았는지 모르면 {@code UNKNOWN}(보낸 것으로 봄). 작업자는 던진 예외를 모두 {@code UNKNOWN}으로 다루므로, 다시 보내도 되는 실패를 예외로
 * 던지면 다시 보내지 않는다.
 */
public interface SmsGateway {

  SmsSendResult send(SmsMessage message);
}
