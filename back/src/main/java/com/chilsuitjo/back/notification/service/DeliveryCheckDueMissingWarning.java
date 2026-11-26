package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.NoticeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 전달 확인 기한 없이 보낸(SENT) 상태인 통지가 있으면 켜질 때 오류 로그를 남긴다. 그런 통지는 전달 결과 작업자가 가져가지 않아 전달됨·최종 실패로 바뀌지 않고,
 * 기다림도 시작되지 않는다. 기한을 지어내거나 실패로 끝내지 않으므로 처리는 사람이 정한다(V 파일 {@code add_notice_delivery_poll_lease}
 * 주석). 개수만 남긴다.
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
    long missing;
    try {
      missing = notices.countSentWithoutDeliveryCheckDue();
    } catch (DataAccessException e) {
      // 알리기만 하는 부품이라 이 조회가 실패해도 앱 시작을 막지 않는다. DB 연결·스키마 오류는 다른 곳에서 그대로 드러난다
      log.warn("전달 확인 기한이 없는 보낸 통지 수를 세지 못함: {}", e.getClass().getSimpleName());
      return;
    }
    if (missing > 0) {
      log.error("전달 확인 기한이 없는 보낸 통지가 있어 전달 결과를 묻지 않음: count={}", missing);
    }
  }
}
