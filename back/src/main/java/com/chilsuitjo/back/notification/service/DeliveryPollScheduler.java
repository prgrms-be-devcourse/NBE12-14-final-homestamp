package com.chilsuitjo.back.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 전달 결과 작업자를 일정 간격으로 돌린다(04 4-2, 04 "켜질 때 + 일정 간격"). 켜진 뒤 첫 실행이 켜질 때 따라잡기이고, 한 번 돌 때 한 묶음이 꽉 차면 밀린
 * 것이 없어질 때까지 이어서 돈다(서버가 오래 꺼져 있었어도 다음 간격까지 기다리지 않게). 가져간 통지는 다음 조회 시각이 지금보다 뒤로 옮겨지므로 같은 실행에서 끝없이 다시
 * 가져가지 않는다.
 *
 * <p>{@code app.delivery.poller.enabled=true}일 때만 만든다. 기본 설정 파일과 테스트 설정은 끄고, 설정이 없어도 꺼진다(가짜 발송기로
 * 뜻하지 않게 돌지 않게). 서비스를 열 때는 배포 설정으로 켠다. 꺼져 있으면 {@link DeliveryPollerOffWarning}이 켜질 때 경고를 남긴다(꺼진 채
 * 정상처럼 보이지 않게).
 *
 * <p>예약 실행 자체는 {@code global/scheduler}가 켠다(09 3장, {@link OutboxPoller}와 같음).
 */
@Component
@ConditionalOnProperty(name = "app.delivery.poller.enabled", havingValue = "true")
public class DeliveryPollScheduler {

  private static final Logger log = LoggerFactory.getLogger(DeliveryPollScheduler.class);

  private final DeliveryResultPoller poller;
  private final int batchSize;

  public DeliveryPollScheduler(
      DeliveryResultPoller poller, @Value("${app.delivery.poller.batch-size:500}") int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("app.delivery.poller.batch-size는 1 이상이어야 해요");
    }
    this.poller = poller;
    this.batchSize = batchSize;
  }

  @Scheduled(
      initialDelayString = "${app.delivery.poller.initial-delay-ms:5000}",
      fixedDelayString = "${app.delivery.poller.interval-ms:30000}")
  void poll() {
    int processed;
    do {
      try {
        processed = poller.runOnce(batchSize);
      } catch (RuntimeException e) {
        // 가져가기(DB)가 실패한 경우. 이번 실행만 멈추고 다음 간격에 다시 돈다
        log.error("전달 결과 조회 실행 실패: error={}", e.getClass().getName());
        return;
      }
    } while (processed == batchSize);
  }
}
