package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.dto.InfoCommand;
import com.chilsuitjo.back.notification.entity.InAppNotification;
import com.chilsuitjo.back.notification.repository.InAppNotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB({@code state.in_app_notification})에 남기는 {@link InAppNotifier}. 원인 업무의 트랜잭션 안에서만 저장하고({@code
 * MANDATORY}), 저장이 실패하면 예외가 그대로 올라가 원인 업무도 함께 되돌아간다(#106, 지금은 번호 인증 해제 한 주제). 만료는 만든 시각 + {@code
 * notification.ttl}(06 0-1, 30일)이다.
 */
@Service
public class JpaInAppNotifier implements InAppNotifier {

  private final InAppNotificationRepository notifications;
  private final ActiveRecipients recipients;
  private final Clock clock;
  private final Duration ttl;

  public JpaInAppNotifier(
      InAppNotificationRepository notifications,
      ActiveRecipients recipients,
      Clock clock,
      @Value("${app.notification.ttl:30d}") Duration ttl) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException("app.notification.ttl은 0보다 크고 마이크로초 단위여야 해요");
    }
    this.notifications = notifications;
    this.recipients = recipients;
    this.clock = clock;
    this.ttl = ttl;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void notify(InfoCommand command) {
    // 탈퇴했거나 탈퇴 중인 회원에게는 만들지 않는다. 받는 회원 행을 붙잡아 탈퇴의 알림 삭제와 순서를 정한다(ActiveRecipients, #424)
    if (!recipients.holdActive(command.recipientMemberId())) {
      return;
    }
    Instant now = clock.instant();
    notifications.saveAndFlush(
        InAppNotification.ofTopic(
            command.recipientMemberId(),
            command.roomRecordId(),
            command.topic(),
            command.target() == null ? null : command.target().kind(),
            command.target() == null ? null : command.target().id(),
            now,
            now.plus(ttl)));
  }
}
