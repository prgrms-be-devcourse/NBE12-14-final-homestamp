package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.InAppNotificationView;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.UnreadCount;
import com.chilsuitjo.back.notification.entity.InAppNotification;
import com.chilsuitjo.back.notification.repository.InAppNotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원의 서비스 안 알림 목록·안 읽은 수·읽음(06 4-1 NOTI-7). 모두 로그인한 회원의 알림만 다룬다. 남의 알림·없는 알림·만료된 알림은 구분하지 않고 {@code
 * NOT_FOUND}다(있는지 드러내지 않음). 읽음은 응답이 아니라 응답 요청·통지·기다림을 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class InAppNotificationService {

  private final InAppNotificationRepository notifications;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<InAppNotificationView> list(UUID memberId) {
    return notifications.findVisible(memberId, clock.instant()).stream()
        .map(InAppNotificationService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public UnreadCount unreadCount(UUID memberId) {
    return new UnreadCount(notifications.countUnread(memberId, clock.instant()));
  }

  /** 읽음 표시. 이미 읽었으면 그대로 성공한다(처음 읽은 시각 유지). */
  @Transactional
  public void read(UUID memberId, UUID notificationId) {
    Instant now = clock.instant();
    if (notifications.markReadIfUnread(notificationId, memberId, now) == 0
        && notifications.findVisible(notificationId, memberId, now).isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
  }

  private static InAppNotificationView view(InAppNotification n) {
    return new InAppNotificationView(
        n.getId(),
        n.getKind(),
        n.getTopic(),
        n.getRoomRecordId(),
        n.getTargetKind() == null ? null : RequestTarget.of(n.getTargetKind(), n.getTargetId()),
        n.getCreatedAt(),
        n.isRead());
  }
}
