package com.chilsuitjo.back.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 취소(#100 U2)가 DB에 닿기 전에 끝나는 경우: save 밖 호출과 빈 목록. */
class SmsCancellationServiceUnitTest {

  private final NoticeRepository notices = mock(NoticeRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final RoomGateReader roomGateReader = mock(RoomGateReader.class);
  private final ActionRequestContext requestContext = new ActionRequestContext();
  private final SmsCancellationService service =
      new SmsCancellationService(notices, outbox, roomGateReader, requestContext);

  @Test
  void save_밖이면_아무것도_읽지_않고_거절한다() {
    assertThatThrownBy(
            () -> service.cancelPendingSms(UUID.randomUUID(), List.of(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(notices, outbox, roomGateReader);
  }

  @Test
  void 빈_목록이면_호실도_잠그지_않고_0이다() {
    ReflectionTestUtils.invokeMethod(
        requestContext, "begin", UUID.randomUUID(), Instant.parse("2026-11-26T01:00:00Z"));
    try {
      assertThat(service.cancelPendingSms(UUID.randomUUID(), List.of())).isZero();
    } finally {
      ReflectionTestUtils.invokeMethod(requestContext, "end");
    }

    verifyNoInteractions(notices, outbox, roomGateReader);
  }

  @Test
  void 인자가_없으면_거절한다() {
    assertThatThrownBy(() -> service.cancelPendingSms(null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.cancelPendingSms(UUID.randomUUID(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
