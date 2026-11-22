package com.chilsuitjo.back.notification.rule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 보낸 문자의 전달 결과를 언제 다시 물을지(04 4-2, #120). 보낸 뒤 1분(첫 조회, {@code Notice.markSent})과 5분에 묻고, 그 뒤로는
 * 15분마다 묻는다. 보낸 뒤 기한({@code app.delivery.unknown-after}, 3일)이 지나면 더 예약하지 않는다.
 *
 * <p>조회 횟수를 따로 적지 않으므로 보낸 시각과 지금 시각만으로 정한다. 그래서 서버가 오래 멈췄다 켜져도 밀린 시각을 차례로 다시 묻지 않고, 지금부터 다음 간격을
 * 잡는다. 순수 함수라 {@code Clock}을 부르지 않는다(09 2장 {@code rule/}).
 */
public final class DeliveryPollSchedule {

  /** 첫 조회: 보낸 뒤 1분({@code Notice.markSent}가 잡음). */
  public static final Duration FIRST_POLL = Duration.ofMinutes(1);

  /** 두 번째 조회: 보낸 뒤 5분. */
  public static final Duration SECOND_POLL = Duration.ofMinutes(5);

  /** 그 뒤 조회 간격. */
  public static final Duration INTERVAL = Duration.ofMinutes(15);

  private DeliveryPollSchedule() {}

  /**
   * 지금 물은 뒤 다음에 물을 시각. 언제나 지금보다 뒤이고 기한을 넘지 않는다. 지금이 이미 기한이거나 지났으면 비어 있다(이번이 마지막 조회, 결과가 없으면 최종 실패).
   *
   * @param sentAt 보낸 시각({@code notice.sent_at})
   * @param now 지금(조회 대상을 가져가는 시각)
   * @param giveUpAfter 보낸 뒤 결과 없이 기다리는 기한
   */
  public static Optional<Instant> next(Instant sentAt, Instant now, Duration giveUpAfter) {
    Instant deadline = deadline(sentAt, giveUpAfter);
    Objects.requireNonNull(now, "now");
    if (!now.isBefore(deadline)) {
      return Optional.empty();
    }
    Instant second = sentAt.plus(SECOND_POLL);
    Instant candidate = now.isBefore(second) ? second : now.plus(INTERVAL);
    return Optional.of(candidate.isAfter(deadline) ? deadline : candidate);
  }

  /** 결과 없이 기다리는 마지막 시각(보낸 시각 + 기한). */
  public static Instant deadline(Instant sentAt, Duration giveUpAfter) {
    Objects.requireNonNull(sentAt, "sentAt");
    Objects.requireNonNull(giveUpAfter, "giveUpAfter");
    return sentAt.plus(giveUpAfter);
  }

  /**
   * 설정의 조회 일정(06 0-1 {@code delivery.poll}, {@code app.delivery.poll})이 이 클래스의 일정과 같은지 본다. 일정은 코드
   * 상수가 정하고 설정은 06 표와 같다는 것을 보이는 값이라, 둘이 다르면 앱을 시작하지 않는다(설정만 바꾸고 동작은 그대로인 일을 막음).
   */
  public static void requireSameAs(List<Duration> configured) {
    List<Duration> schedule = List.of(FIRST_POLL, SECOND_POLL, INTERVAL);
    if (!schedule.equals(configured)) {
      throw new IllegalStateException(
          "app.delivery.poll이 코드의 조회 일정(1m,5m,15m)과 달라요: " + configured);
    }
  }
}
