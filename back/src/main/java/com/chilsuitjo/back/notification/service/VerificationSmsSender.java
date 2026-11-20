package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.VerificationSendResult;

/**
 * 인증 문자 바로 보내기(04 4-2 "인증 문자는 아웃박스를 쓰지 않는다", decisions 2026-11-18). auth가 {@code
 * phone_verification} 행을 <b>커밋한 뒤</b> 부른다.
 *
 * <p>아웃박스·통지·모으기·밤 보류·한도 미루기를 쓰지 않고, 수신 거부 목록·문자 설정과 상관없이 보낸다(03 K "인증 문자"). {@code code}(원문)는 이 호출
 * 안에서만 쓰고 저장·로그에 남기지 않는다. 번호도 로그에 남기지 않는다. 자동으로 다시 보내지 않는다. 서비스 전체 한도({@code sms-total})는 auth가 인증
 * 요청 트랜잭션에서 먼저 센다(05 {@code rate_counter}).
 */
public interface VerificationSmsSender {

  /** 시간 제한(5초) 안에 답이 없으면 {@link VerificationSendResult#UNKNOWN}. 예외를 던지지 않는다. */
  VerificationSendResult send(String phone, String code);
}
