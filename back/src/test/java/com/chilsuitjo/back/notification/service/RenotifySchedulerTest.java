package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** 재통지 작업자의 예약 실행 부품. 실제 시각을 기다리지 않도록 {@code poll()}을 직접 부른다. */
class RenotifySchedulerTest {

  private final RenotifyWorker worker = mock(RenotifyWorker.class);

  @Test
  void 문자_발송이_꺼져_있으면_켜지_않아_재통지_한_번을_보내지_않고_쓰지_않는다() {
    assertThatThrownBy(() -> new RenotifyScheduler(worker, 100, "disabled"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.sms.mode=disabled");

    verify(worker, never()).runOnce(100);
  }

  @Test
  void 문자를_보낼_수_있는_방식이면_켜지고_한_번_돌_때_작업자를_부른다() {
    new RenotifyScheduler(worker, 100, "fake").poll();

    verify(worker).runOnce(100);
  }

  @Test
  void 페이지_크기가_1보다_작으면_켜지_않는다() {
    assertThatThrownBy(() -> new RenotifyScheduler(worker, 0, "fake"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
