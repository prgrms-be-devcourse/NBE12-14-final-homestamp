package com.chilsuitjo.back.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 알림 정리를 켜질 때(첫 지연 뒤)와 일정 간격으로 돌린다(04 "켜질 때 + 일정 간격", 정해진 시각이 아니라 밤에 놓치지 않음). {@code
 * app.notification.cleanup.enabled=false}면 만들지 않는다(테스트는 {@link InAppNotificationCleanup}을 직접 부름). 한
 * 번이 실패하면 예약 실행이 로그를 남기고 다음 간격에 다시 돈다.
 *
 * <p>예약 실행 자체는 {@code global/scheduler}가 켠다(09 3장, {@link OutboxPoller}와 같음).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.notification.cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InAppNotificationCleanupPoller {

  private final InAppNotificationCleanup cleanup;

  @Scheduled(
      initialDelayString = "${app.notification.cleanup.initial-delay-ms:60000}",
      fixedDelayString = "${app.notification.cleanup.interval-ms:3600000}")
  void purge() {
    cleanup.purgeExpired();
  }
}
