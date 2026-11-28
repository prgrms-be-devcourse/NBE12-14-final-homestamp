package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.notification.repository.InAppNotificationRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴한 회원의 서비스 안 알림을 지운다(03 K "탈퇴하면 바로 삭제", 05 {@code in_app_notification}, #424). 탈퇴 트랜잭션이 회원 행을 쓰기
 * 잠금한 뒤 부른다. 그 회원이 <b>받은</b> 알림만 지우고(모든 호실·계정 알림), 상대가 받은 알림과 행동 기록은 그대로다.
 */
@Service
@RequiredArgsConstructor
public class MemberNotifications {

  private final InAppNotificationRepository notifications;

  /**
   * 그 회원이 받은 알림을 모두 지우고 지운 줄 수를 돌려준다. 부르는 쪽 트랜잭션에서만({@code MANDATORY}) 돌고, 실패하면 예외가 올라가 탈퇴 전체가
   * 되돌아간다. 삭제 기록은 남기지 않는다(정리 작업과 같음).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteAllOf(UUID memberId) {
    return notifications.deleteAllOfMember(Objects.requireNonNull(memberId, "memberId"));
  }
}
