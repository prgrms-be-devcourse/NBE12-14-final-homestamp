package com.chilsuitjo.back.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재통지 작업자를 일정 간격으로 돌린다(04 "켜질 때 + 일정 간격"). 켜진 뒤 첫 실행이 켜질 때 따라잡기다(06 OPS-1a: 꺼진 동안 지난 기한을 한 번씩).
 *
 * <p>{@code app.wait.renotify-worker.enabled=true}일 때만 만든다. 기본 설정 파일과 테스트 설정은 끄고, 설정이 없어도 꺼진다. 요청 →
 * 통지(NOTI-1)와 실제 전달 결과 조회가 운영에서 켜지기 전에는 켜지 않는다(재통지가 갈 실제 경로가 준비되지 않음).
 *
 * <p>예약 실행 자체는 {@code global/scheduler}가 켠다(09 3장, {@link OutboxPoller}와 같음).
 */
@Component
@ConditionalOnProperty(name = "app.wait.renotify-worker.enabled", havingValue = "true")
public class RenotifyScheduler {

  private static final Logger log = LoggerFactory.getLogger(RenotifyScheduler.class);

  private final RenotifyWorker worker;
  private final int pageSize;

  public RenotifyScheduler(
      RenotifyWorker worker, @Value("${app.wait.renotify-worker.page-size:100}") int pageSize) {
    if (pageSize < 1) {
      throw new IllegalArgumentException("app.wait.renotify-worker.page-size는 1 이상이어야 해요");
    }
    this.worker = worker;
    this.pageSize = pageSize;
  }

  @Scheduled(
      initialDelayString = "${app.wait.renotify-worker.initial-delay-ms:5000}",
      fixedDelayString = "${app.wait.renotify-worker.interval-ms:60000}")
  void poll() {
    try {
      worker.runOnce(pageSize);
    } catch (RuntimeException e) {
      // 후보 읽기(DB)가 실패한 경우. 이번 실행만 멈추고 다음 간격에 다시 돈다
      log.error("재통지 작업자 실행 실패: error={}", e.getClass().getName());
    }
  }
}
