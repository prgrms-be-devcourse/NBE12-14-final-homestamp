package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.dto.NoticeStep;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 보낸 측 통지 상태 한 줄(03 K "보낸 사람에게", 06 NOTI-4a). */
class NoticeLineTest {

  private static final Instant T = Instant.parse("2026-11-27T01:00:00Z");

  @Test
  void 통지가_없으면_한_줄도_없다() {
    assertThat(NoticeLine.of(List.of())).isNull();
    assertThat(NoticeLine.renotified(List.of())).isFalse();
  }

  @Test
  void 문자는_상태대로_한_줄이고_발송_성공이라는_줄은_없다() {
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.REQUESTED)))).isEqualTo(NoticeLine.SOON);
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.SENT)))).isEqualTo(NoticeLine.SENT);
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.DELIVERED)))).isEqualTo(NoticeLine.DELIVERED);
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.VIEWED)))).isEqualTo(NoticeLine.VIEWED);
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.FAILED)))).isEqualTo(NoticeLine.FAILED);
  }

  @Test
  void 서비스_안_알림은_열기_전까지_알림으로_알렸어요이고_열면_열어_봤어요다() {
    assertThat(NoticeLine.of(List.of(inApp(NoticeStatus.DELIVERED)))).isEqualTo(NoticeLine.IN_APP);
    assertThat(NoticeLine.of(List.of(inApp(NoticeStatus.VIEWED)))).isEqualTo(NoticeLine.VIEWED);
  }

  @Test
  void 마지막으로_만든_통지가_지금이고_재통지면_표시한다() {
    List<NoticeStep> history =
        List.of(sms(NoticeStatus.DELIVERED), step(NoticeKind.RENOTIFY, NoticeStatus.SENT));

    assertThat(NoticeLine.of(history)).isEqualTo(NoticeLine.SENT);
    assertThat(NoticeLine.renotified(history)).isTrue();
  }

  @Test
  void 실패_뒤_다시_보낸_통지가_지금이다() {
    List<NoticeStep> history = List.of(sms(NoticeStatus.FAILED), sms(NoticeStatus.REQUESTED));

    assertThat(NoticeLine.of(history)).isEqualTo(NoticeLine.SOON);
    assertThat(NoticeLine.renotified(history)).isFalse();
  }

  @Test
  void 보내기_전_취소는_줄을_정하지_않는다() {
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.CANCELLED)))).isNull();
    assertThat(NoticeLine.of(List.of(sms(NoticeStatus.FAILED), sms(NoticeStatus.CANCELLED))))
        .isEqualTo(NoticeLine.FAILED);
  }

  private static NoticeStep sms(NoticeStatus status) {
    return step(NoticeKind.REQUEST, status);
  }

  private static NoticeStep inApp(NoticeStatus status) {
    return new NoticeStep(NoticeKind.REQUEST, NoticeChannel.IN_APP, status, T, null, T, null, null);
  }

  private static NoticeStep step(NoticeKind kind, NoticeStatus status) {
    return new NoticeStep(kind, NoticeChannel.SMS, status, T, null, null, null, null);
  }
}
