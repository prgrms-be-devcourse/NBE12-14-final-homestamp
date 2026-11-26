package com.chilsuitjo.back.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 통지의 전달 결과 전이(#120 2단계). 보낸(SENT) 통지만 전달됨·최종 실패로 바뀌고, 상태는 앞으로만 간다(04 4-2). 시각은 부르는 쪽이 넘긴 값을 그대로
 * 남긴다(어느 시각을 쓸지는 결정 후보).
 */
class NoticeTest {

  private static final Instant CREATED = Instant.parse("2026-11-23T01:00:00Z");
  private static final Instant SENT_AT = Instant.parse("2026-11-23T01:00:10Z");
  private static final Instant LATER = Instant.parse("2026-11-23T01:05:00Z");
  private static final Instant MUCH_LATER = Instant.parse("2026-11-23T02:00:00Z");
  private static final Instant DUE = SENT_AT.plus(Duration.ofDays(3));

  @Test
  void 보낸_통지가_전달되면_전달됨과_넘긴_시각이_남고_더_묻지_않는다() {
    Notice notice = sent();

    notice.markDelivered(LATER);

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.DELIVERED);
    assertThat(notice.getDeliveredAt()).isEqualTo(LATER);
    assertThat(notice.getSentAt()).isEqualTo(SENT_AT);
    assertThat(notice.getNextPollAt()).isNull();
    assertThat(notice.getFailedAt()).isNull();
  }

  @Test
  void 보낸_통지가_최종_실패하면_실패와_넘긴_시각이_남고_더_묻지_않는다() {
    Notice notice = sent();

    notice.markDeliveryFailed(LATER);

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.FAILED);
    assertThat(notice.getFailedAt()).isEqualTo(LATER);
    assertThat(notice.getSentAt()).isEqualTo(SENT_AT);
    assertThat(notice.getNextPollAt()).isNull();
    assertThat(notice.getDeliveredAt()).isNull();
  }

  @Test
  void 보내기_전_통지는_전달_결과로_바뀌지_않는다() {
    Notice delivered = requested();
    Notice failed = requested();

    delivered.markDelivered(LATER);
    failed.markDeliveryFailed(LATER);

    assertThat(delivered.getStatus()).isEqualTo(NoticeStatus.REQUESTED);
    assertThat(delivered.getDeliveredAt()).isNull();
    assertThat(failed.getStatus()).isEqualTo(NoticeStatus.REQUESTED);
    assertThat(failed.getFailedAt()).isNull();
  }

  @Test
  void 이미_전달된_통지는_다시_온_결과로_시각이나_상태가_바뀌지_않는다() {
    Notice notice = sent();
    notice.markDelivered(LATER);

    notice.markDelivered(MUCH_LATER);
    notice.markDeliveryFailed(MUCH_LATER);

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.DELIVERED);
    assertThat(notice.getDeliveredAt()).isEqualTo(LATER);
    assertThat(notice.getFailedAt()).isNull();
  }

  @Test
  void 이미_실패한_통지는_뒤늦은_전달_결과로_바뀌지_않는다() {
    Notice notice = sent();
    notice.markDeliveryFailed(LATER);

    notice.markDelivered(MUCH_LATER);

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.FAILED);
    assertThat(notice.getDeliveredAt()).isNull();
    assertThat(notice.getFailedAt()).isEqualTo(LATER);
  }

  @Test
  void 보내기_전_최종_실패는_기존대로_보내기_전에만_된다() {
    Notice requested = requested();
    requested.markFailed(LATER);
    assertThat(requested.getStatus()).isEqualTo(NoticeStatus.FAILED);
    assertThat(requested.getFailedAt()).isEqualTo(LATER);

    // 보낸 통지에는 기존 markFailed가 듣지 않는다(전달 실패는 markDeliveryFailed)
    Notice sent = sent();
    sent.markFailed(LATER);
    assertThat(sent.getStatus()).isEqualTo(NoticeStatus.SENT);
    assertThat(sent.getFailedAt()).isNull();
  }

  @Test
  void 보내면_보낸_시각과_1분_뒤_첫_조회가_정해지고_두_번_보내지_않는다() {
    Notice notice = requested();

    notice.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), DUE);
    notice.markSent("fake-2", LATER, LATER.plusSeconds(60), LATER.plus(Duration.ofMinutes(5)));

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.SENT);
    assertThat(notice.getSentAt()).isEqualTo(SENT_AT);
    assertThat(notice.getProviderMessageId()).isEqualTo("fake-1");
    assertThat(notice.getNextPollAt()).isEqualTo(SENT_AT.plusSeconds(60));
    // 두 번째 markSent는 무시되므로 처음 정한 기한이 그대로다
    assertThat(notice.getDeliveryCheckDueAt()).isEqualTo(DUE);
  }

  @Test
  void 보내기_작업자가_계산한_첫_조회_시각을_그대로_저장한다() {
    Notice notice = requested();
    Instant configuredFirst = SENT_AT.plusSeconds(120);

    notice.markSent("fake-1", SENT_AT, configuredFirst, DUE);

    assertThat(notice.getNextPollAt()).isEqualTo(configuredFirst);
    assertThat(notice.getDeliveryCheckDueAt()).isEqualTo(DUE);
  }

  @Test
  void 보내기_전에는_전달_확인_기한이_없다() {
    assertThat(requested().getDeliveryCheckDueAt()).isNull();
  }

  @Test
  void 전달_확인_기한이_없거나_보낸_시각보다_뒤가_아니면_보내지_않는다() {
    Notice missing = requested();
    assertThatThrownBy(() -> missing.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), null))
        .isInstanceOf(NullPointerException.class);

    Notice same = requested();
    assertThatThrownBy(() -> same.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), SENT_AT))
        .isInstanceOf(IllegalArgumentException.class);

    Notice before = requested();
    assertThatThrownBy(() ->
            before.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), SENT_AT.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);

    for (Notice notice : List.of(missing, same, before)) {
      assertThat(notice.getStatus()).isEqualTo(NoticeStatus.REQUESTED);
      assertThat(notice.getSentAt()).isNull();
      assertThat(notice.getDeliveryCheckDueAt()).isNull();
    }
  }

  @Test
  void 첫_조회_시각이_전달_확인_기한을_넘으면_보내지_않는다() {
    Notice notice = requested();
    Instant due = SENT_AT.plusSeconds(30);

    assertThatThrownBy(() -> notice.markSent("fake-1", SENT_AT, due.plusNanos(1_000), due))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.REQUESTED);

    // 기한과 같은 첫 조회는 받는다(기한이 첫 조회 간격보다 짧은 설정)
    notice.markSent("fake-1", SENT_AT, due, due);
    assertThat(notice.getNextPollAt()).isEqualTo(due);
  }

  @Test
  void 조회_세대와_다음_조회가_바뀌고_결과가_적혀도_전달_확인_기한은_그대로다() {
    Notice delivered = sent();
    UUID token = UUID.randomUUID();
    delivered.claimDeliveryPoll(token, LATER);
    delivered.startDeliveryPoll(token, LATER, LATER.plusSeconds(120));
    delivered.finishDeliveryPoll(token, MUCH_LATER);
    delivered.scheduleNextPoll(MUCH_LATER.plusSeconds(60));
    assertThat(delivered.getDeliveryCheckDueAt()).isEqualTo(DUE);
    delivered.markDelivered(MUCH_LATER);
    assertThat(delivered.getDeliveryCheckDueAt()).isEqualTo(DUE);

    Notice failed = sent();
    failed.markDeliveryFailed(MUCH_LATER);
    assertThat(failed.getDeliveryCheckDueAt()).isEqualTo(DUE);
  }

  @Test
  void 전달_결과_시각이_없으면_거절한다() {
    Notice notice = sent();

    assertThatThrownBy(() -> notice.markDelivered(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> notice.markDeliveryFailed(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.SENT);
  }

  @Test
  void 보낸_통지만_다음_조회_시각을_옮긴다() {
    Notice sent = sent();
    sent.scheduleNextPoll(MUCH_LATER);
    assertThat(sent.getNextPollAt()).isEqualTo(MUCH_LATER);
    assertThat(sent.getStatus()).isEqualTo(NoticeStatus.SENT);

    Notice requested = requested();
    requested.scheduleNextPoll(MUCH_LATER);
    assertThat(requested.getNextPollAt()).isNull();

    Notice delivered = sent();
    delivered.markDelivered(LATER);
    delivered.scheduleNextPoll(MUCH_LATER);
    assertThat(delivered.getNextPollAt()).isNull();

    assertThatThrownBy(() -> sent.scheduleNextPoll(null)).isInstanceOf(NullPointerException.class);
  }

  private static Notice requested() {
    return new Notice(
        UUID.randomUUID(),
        null,
        null,
        UUID.randomUUID(),
        NoticeKind.REQUEST,
        NoticeChannel.SMS,
        CREATED,
        CREATED);
  }

  private static Notice sent() {
    Notice notice = requested();
    notice.markSent("fake-1", SENT_AT, SENT_AT.plusSeconds(60), DUE);
    return notice;
  }
}
