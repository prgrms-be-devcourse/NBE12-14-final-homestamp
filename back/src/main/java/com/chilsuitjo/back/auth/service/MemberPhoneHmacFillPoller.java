package com.chilsuitjo.back.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link MemberPhoneHmacFill}을 켜질 때와 일정 간격으로 돌린다(04 "켜질 때 + 일정 간격", #462). 블루/그린 전환 중 이전 앱이 만든 빈 행은
 * 이전 앱이 내려간 뒤 다음 간격에 채워진다. {@code app.member.phone-hmac-fill.enabled=false}면 만들지 않는다(채우기를 직접 부르는
 * 테스트).
 *
 * <p>2단계 V 파일(엄격한 CHECK)이 들어가면 빈 행이 생기지 않으므로 이 작업을 뺀다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.member.phone-hmac-fill.enabled",
    havingValue = "true",
    matchIfMissing = true)
class MemberPhoneHmacFillPoller {

  private final MemberPhoneHmacFill fill;

  @Scheduled(
      initialDelayString = "${app.member.phone-hmac-fill.initial-delay-ms:10000}",
      fixedDelayString = "${app.member.phone-hmac-fill.interval-ms:600000}")
  void run() {
    fill.fillAll();
  }
}
