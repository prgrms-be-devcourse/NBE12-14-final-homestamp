package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chilsuitjo.back.notification.repository.NoticeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class DeliveryCheckDueMissingWarningTest {

  private final NoticeRepository notices = mock(NoticeRepository.class);
  private final DeliveryCheckDueMissingWarning warning =
      new DeliveryCheckDueMissingWarning(notices);

  @Test
  void 기한_없는_보낸_통지가_있으면_개수를_오류로_남긴다(CapturedOutput output) {
    when(notices.countSentWithoutDeliveryCheckDue()).thenReturn(3L);

    warning.warn();

    assertThat(output).contains("ERROR").contains("count=3");
  }

  @Test
  void 없으면_남기지_않는다(CapturedOutput output) {
    when(notices.countSentWithoutDeliveryCheckDue()).thenReturn(0L);

    warning.warn();

    assertThat(output).doesNotContain("전달 확인 기한이 없는 보낸 통지");
  }

  /** 알리기만 하는 부품이라 세는 조회가 실패해도 앱 시작(ApplicationReadyEvent)을 막지 않는다. */
  @Test
  void 세는_조회가_실패해도_예외를_올리지_않고_경고만_남긴다(CapturedOutput output) {
    when(notices.countSentWithoutDeliveryCheckDue())
        .thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatCode(warning::warn).doesNotThrowAnyException();
    assertThat(output).contains("WARN").contains("세지 못함").doesNotContain("db down");
  }
}
