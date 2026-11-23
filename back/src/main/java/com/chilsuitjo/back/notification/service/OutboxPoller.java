package com.chilsuitjo.back.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 작업자를 일정 간격으로 돌린다(04 "켜질 때 + 일정 간격"). {@code app.outbox.worker.enabled=false}면 만들지 않는다(작업자를
 * 직접 부르는 테스트).
 *
 * <p>예약 실행 자체는 {@code global/scheduler}가 켠다. global은 notification을 모르므로 이 클래스는 notification에 둔다(09
 * 3장).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.outbox.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPoller {

  private final OutboxWorker worker;

  @Scheduled(
      initialDelayString = "${app.outbox.worker.interval-ms:5000}",
      fixedDelayString = "${app.outbox.worker.interval-ms:5000}")
  void poll() {
    worker.runOnce();
  }
}
