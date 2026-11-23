package com.chilsuitjo.back.notification.rule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 보낸 문자의 전달 결과 조회 일정(06 0-1 {@code delivery.poll}). 세 설정값은 첫 조회까지의 시간, 보낸 뒤 두 번째 조회까지의 시간, 그 뒤 조회
 * 간격이다. 기한({@code app.delivery.unknown-after})을 넘지 않게 예약한다.
 *
 * <p>조회 횟수를 따로 적지 않으므로 보낸 시각과 지금 시각만으로 정한다. 서버가 오래 멈췄다 켜져도 밀린 시각을 차례로 다시 묻지 않고, 지금부터 다음 간격을 잡는다.
 * 계산은 순수 함수이며 {@code Clock}을 부르지 않는다(09 2장 {@code rule/}).
 */
public final class DeliveryPollSchedule {

  /** 기본 첫 조회: 보낸 뒤 1분. */
  public static final Duration FIRST_POLL = Duration.ofMinutes(1);

  /** 기본 두 번째 조회: 보낸 뒤 5분. */
  public static final Duration SECOND_POLL = Duration.ofMinutes(5);

  /** 기본 이후 조회 간격: 15분. */
  public static final Duration INTERVAL = Duration.ofMinutes(15);

  private final Duration firstPoll;
  private final Duration secondPoll;
  private final Duration interval;

  public DeliveryPollSchedule(List<Duration> configured) {
    if (configured == null || configured.size() != 3) {
      throw new IllegalArgumentException("app.delivery.poll은 첫 조회·두 번째 조회·이후 간격 세 값이 필요해요");
    }
    for (Duration delay : configured) {
      if (delay == null
          || delay.compareTo(Duration.ofNanos(1_000)) < 0
          || delay.getNano() % 1_000 != 0) {
        throw new IllegalArgumentException("app.delivery.poll의 간격은 양수이고 마이크로초 단위여야 해요");
      }
    }
    if (configured.get(1).compareTo(configured.get(0)) <= 0) {
      throw new IllegalArgumentException("app.delivery.poll의 두 번째 조회는 첫 조회보다 뒤여야 해요");
    }
    this.firstPoll = configured.get(0);
    this.secondPoll = configured.get(1);
    this.interval = configured.get(2);
  }

  /** 첫 조회 시각. 결과 확인 기한보다 늦게 예약하지 않는다. */
  public Instant first(Instant sentAt, Duration giveUpAfter) {
    Instant deadline = deadline(sentAt, giveUpAfter);
    Instant first = sentAt.plus(firstPoll);
    return first.isAfter(deadline) ? deadline : first;
  }

  /** 지금 물은 뒤 다음에 물을 시각. 언제나 지금보다 뒤이고 기한을 넘지 않는다. 지금이 이미 기한이거나 지났으면 비어 있다. */
  public Optional<Instant> next(Instant sentAt, Instant now, Duration giveUpAfter) {
    Instant deadline = deadline(sentAt, giveUpAfter);
    Objects.requireNonNull(now, "now");
    if (!now.isBefore(deadline)) {
      return Optional.empty();
    }
    Instant second = sentAt.plus(secondPoll);
    Instant candidate = now.isBefore(second) ? second : now.plus(interval);
    return Optional.of(candidate.isAfter(deadline) ? deadline : candidate);
  }

  /** 결과 없이 기다리는 마지막 시각(보낸 시각 + 기한). */
  public static Instant deadline(Instant sentAt, Duration giveUpAfter) {
    Objects.requireNonNull(sentAt, "sentAt");
    Objects.requireNonNull(giveUpAfter, "giveUpAfter");
    return sentAt.plus(giveUpAfter);
  }

  /** 완료되지 않은 선점이나 잘못된 통지를 다시 가져갈 복구용 간격. */
  public Duration interval() {
    return interval;
  }
}
