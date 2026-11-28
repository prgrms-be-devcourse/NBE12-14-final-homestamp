package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.rule.SmsMode;
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
 * 통지(NOTI-1)와 실제 전달 결과 조회가 운영에서 켜지기 전에는 켜지 않는다(재통지가 갈 실제 경로가 준비되지 않음). 문자 발송이 꺼진 {@code
 * app.sms.mode=disabled}와 함께 켜면 기동하지 않는다.
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
      RenotifyWorker worker,
      @Value("${app.wait.renotify-worker.page-size:100}") int pageSize,
      @Value("${" + SmsMode.PROPERTY + ":}") String smsMode) {
    // disabled면 OutboxWorker가 보내지 않고 끝내는데 재통지는 이미 남아 "이미 재통지함"이 된다. 응답 기회당 한 번(03 1-4)을 실제로
    // 보내지 않고 써 버리지 않게 이 조합으로는 기동하지 않는다(SmsModeGuard처럼 기동 전에 거절)
    if (SmsMode.parse(smsMode).orElse(null) == SmsMode.DISABLED) {
      throw new IllegalStateException(
          "문자 발송이 꺼져 있으면(app.sms.mode=disabled) 재통지 작업자를 켤 수 없어요(app.wait.renotify-worker.enabled)");
    }
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
