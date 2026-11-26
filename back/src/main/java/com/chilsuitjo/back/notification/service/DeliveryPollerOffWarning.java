package com.chilsuitjo.back.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 전달 결과 작업자가 꺼져 있으면 켜질 때 경고를 남긴다. 꺼져 있으면 보낸 문자가 전달됨·최종 실패로 바뀌지 않아 "받았는지 확인 중"에 머물고, 기다림(48시간)도 시작되지
 * 않는다(04 4-2). 테스트에서는 일부러 끄므로 이 경고가 보인다.
 */
@Component
@ConditionalOnProperty(
    name = "app.delivery.poller.enabled",
    havingValue = "false",
    matchIfMissing = true)
class DeliveryPollerOffWarning {

  private static final Logger log = LoggerFactory.getLogger(DeliveryPollerOffWarning.class);

  @EventListener(ApplicationReadyEvent.class)
  void warn() {
    log.warn("전달 결과 조회 작업자가 꺼져 있음(app.delivery.poller.enabled): 보낸 문자의 전달 결과를 묻지 않음");
  }
}
