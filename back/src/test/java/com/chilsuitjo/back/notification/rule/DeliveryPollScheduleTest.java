package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 전달 결과를 다시 물을 시각(#120 3-A): 보낸 뒤 1분·5분, 그 뒤 15분마다, 보낸 뒤 3일까지. */
class DeliveryPollScheduleTest {

  private static final Instant SENT = Instant.parse("2026-11-23T01:00:00Z");
  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final Instant DEADLINE = SENT.plus(THREE_DAYS);

  @Test
  void 첫_조회_뒤에는_보낸_뒤_5분에_묻는다() {
    assertThat(next(SENT.plusSeconds(60))).isEqualTo(SENT.plus(Duration.ofMinutes(5)));
    assertThat(next(SENT.plus(Duration.ofSeconds(299))))
        .isEqualTo(SENT.plus(Duration.ofMinutes(5)));
  }

  @Test
  void 보낸_뒤_5분_조회부터는_지금부터_15분_뒤에_묻는다() {
    Instant fiveMinutes = SENT.plus(Duration.ofMinutes(5));

    assertThat(next(fiveMinutes)).isEqualTo(fiveMinutes.plus(Duration.ofMinutes(15)));
    assertThat(next(SENT.plus(Duration.ofMinutes(20))))
        .isEqualTo(SENT.plus(Duration.ofMinutes(35)));
  }

  @Test
  void 서버가_오래_멈췄다_켜지면_밀린_시각이_아니라_지금부터_15분_뒤다() {
    Instant restarted = SENT.plus(Duration.ofHours(10)).plusSeconds(7);

    assertThat(next(restarted)).isEqualTo(restarted.plus(Duration.ofMinutes(15)));
  }

  @Test
  void 기한에_가까우면_기한을_넘지_않고_기한에_묻는다() {
    assertThat(next(DEADLINE.minus(Duration.ofMinutes(5)))).isEqualTo(DEADLINE);
    assertThat(next(DEADLINE.minusSeconds(1))).isEqualTo(DEADLINE);
  }

  @Test
  void 기한이_되거나_지나면_더_예약하지_않는다() {
    assertThat(DeliveryPollSchedule.next(SENT, DEADLINE, THREE_DAYS)).isEmpty();
    assertThat(DeliveryPollSchedule.next(SENT, DEADLINE.plus(Duration.ofDays(1)), THREE_DAYS))
        .isEmpty();
  }

  @Test
  void 다음_시각은_언제나_지금보다_뒤이고_기한을_넘지_않는다() {
    for (Instant now = SENT.minusSeconds(30); now.isBefore(DEADLINE); now = now.plusSeconds(97)) {
      Instant next = next(now);
      assertThat(next).isAfter(now).isBeforeOrEqualTo(DEADLINE);
    }
  }

  @Test
  void 기한은_보낸_시각에_기다리는_기간을_더한_시각이다() {
    assertThat(DeliveryPollSchedule.deadline(SENT, THREE_DAYS)).isEqualTo(DEADLINE);
  }

  @Test
  void 설정의_조회_일정이_코드와_다르면_거절한다() {
    DeliveryPollSchedule.requireSameAs(
        List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

    assertThatThrownBy(() ->
            DeliveryPollSchedule.requireSameAs(
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(10))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> DeliveryPollSchedule.requireSameAs(List.of(Duration.ofMinutes(1))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Instant next(Instant now) {
    return DeliveryPollSchedule.next(SENT, now, THREE_DAYS).orElseThrow();
  }
}
