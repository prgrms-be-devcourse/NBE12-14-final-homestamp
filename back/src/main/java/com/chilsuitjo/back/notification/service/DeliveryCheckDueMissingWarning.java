package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.NoticeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 전달 확인 기한 없이 보낸(SENT) 상태인 통지가 있으면 켜질 때 오류 로그를 남긴다. 그런 통지는 전달 결과 작업자가 가져가지 않아 전달됨·최종 실패로 바뀌지 않고,
 * 기다림도 시작되지 않는다. 기한을 지어내거나 실패로 끝내지 않으므로 처리는 사람이 정한다(V9 주석). 개수만 남긴다.
 */
@Component
class DeliveryCheckDueMissingWarning {

  private static final Logger log = LoggerFactory.getLogger(DeliveryCheckDueMissingWarning.class);

  private final NoticeRepository notices;

  DeliveryCheckDueMissingWarning(NoticeRepository notices) {
    this.notices = notices;
  }

  @EventListener(ApplicationReadyEvent.class)
  void warn() {
    long missing = notices.countSentWithoutDeliveryCheckDue();
    if (missing > 0) {
      log.error("전달 확인 기한이 없는 보낸 통지가 있어 전달 결과를 묻지 않음: count={}", missing);
    }
  }
}
