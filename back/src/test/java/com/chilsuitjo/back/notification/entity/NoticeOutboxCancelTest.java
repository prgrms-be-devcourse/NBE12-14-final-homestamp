package com.chilsuitjo.back.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import com.chilsuitjo.back.notification.rule.OutboxStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 보내기 전 취소(#100 U2): 통지는 REQUESTED에서만, 문자 줄은 PENDING에서만 CANCELLED가 된다. */
class NoticeOutboxCancelTest {

  private static final Instant NOW = Instant.parse("2026-11-26T01:00:00Z");

  @Test
  void 발송_요청된_통지는_취소된다() {
    Notice notice = notice();

    assertThat(notice.markCancelled()).isTrue();

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CANCELLED);
    assertThat(notice.getSentAt()).isNull();
    assertThat(notice.getDeliveredAt()).isNull();
    assertThat(notice.getViewedAt()).isNull();
    assertThat(notice.getFailedAt()).isNull();
  }

  @ParameterizedTest
  @EnumSource(
      value = NoticeStatus.class,
      names = {"SENT", "DELIVERED", "VIEWED", "FAILED"})
  void 발송_요청이_아닌_통지는_취소하지_않는다(NoticeStatus status) {
    Notice notice = notice();
    ReflectionTestUtils.setField(notice, "status", status);

    assertThat(notice.markCancelled()).isFalse();

    assertThat(notice.getStatus()).isEqualTo(status);
  }

  @Test
  void 이미_취소된_통지를_다시_취소해도_바뀌지_않는다() {
    Notice notice = notice();
    notice.markCancelled();

    assertThat(notice.markCancelled()).isFalse();

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CANCELLED);
  }

  @Test
  void 취소된_통지는_뒤늦은_발송_결과로_바뀌지_않는다() {
    Notice notice = notice();
    notice.markCancelled();

    notice.markSent("provider-1", NOW);
    notice.markFailed(NOW);

    assertThat(notice.getStatus()).isEqualTo(NoticeStatus.CANCELLED);
    assertThat(notice.getSentAt()).isNull();
    assertThat(notice.getFailedAt()).isNull();
    assertThat(notice.getProviderMessageId()).isNull();
  }

  @Test
  void 대기_중인_문자_줄은_취소되고_다른_값은_그대로다() {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);

    assertThat(row.markCancelled()).isTrue();

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.CANCELLED);
    assertThat(row.getAttempts()).isZero();
    assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
  }

  @Test
  void 다시_시도_대기_중인_문자_줄도_취소되고_시도_횟수와_다음_시각은_그대로다() {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);
    row.markSending();
    Instant next = NOW.plusSeconds(60);
    row.retryAt(next);

    assertThat(row.markCancelled()).isTrue();

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.CANCELLED);
    assertThat(row.getAttempts()).isEqualTo(1);
    assertThat(row.getNextAttemptAt()).isEqualTo(next);
  }

  @ParameterizedTest
  @EnumSource(
      value = OutboxStatus.class,
      names = {"SENDING", "DONE", "FAILED"})
  void 대기_중이_아닌_문자_줄은_취소하지_않는다(OutboxStatus status) {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);
    row.markSending();
    switch (status) {
      case DONE -> row.markDone();
      case FAILED -> row.markFailed();
      default -> {}
    }

    assertThat(row.markCancelled()).isFalse();

    assertThat(row.getStatus()).isEqualTo(status);
    assertThat(row.getAttempts()).isEqualTo(1);
  }

  @Test
  void 작업자가_가져간_줄은_보내기_전_취소로만_취소되고_시도_횟수는_그대로다() {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);
    row.markSending();

    assertThat(row.markCancelledBeforeSend()).isTrue();

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.CANCELLED);
    assertThat(row.getAttempts()).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = OutboxStatus.class,
      names = {"PENDING", "DONE", "FAILED", "CANCELLED"})
  void 가져간_줄이_아니면_보내기_전_취소는_바꾸지_않는다(OutboxStatus status) {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);
    ReflectionTestUtils.setField(row, "status", status);

    assertThat(row.markCancelledBeforeSend()).isFalse();

    assertThat(row.getStatus()).isEqualTo(status);
  }

  @Test
  void 이미_취소된_문자_줄을_다시_취소해도_바뀌지_않는다() {
    Outbox row = Outbox.sms(UUID.randomUUID(), NOW, NOW);
    row.markCancelled();

    assertThat(row.markCancelled()).isFalse();

    assertThat(row.getStatus()).isEqualTo(OutboxStatus.CANCELLED);
  }

  private static Notice notice() {
    return new Notice(
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        NoticeKind.INVITE,
        NoticeChannel.SMS,
        NOW,
        NOW);
  }
}
