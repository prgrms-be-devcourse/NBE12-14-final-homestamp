package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.InAppTopic;
import java.util.Objects;
import java.util.UUID;

/**
 * 응답이 필요 없는 서비스 안 알림 하나(05 {@code in_app_notification}, 종류 INFO·SERVICE). 읽음은 확인이 아니다(03 K). 부르는 쪽은
 * 팩토리를 쓴다. 생성자를 직접 불러도 같은 검사를 해서 틀리면 만들 때 예외다.
 *
 * <p>지금은 계정 주제만 있어 {@link #account}뿐이다. 호실 주제·묶음(하루 묶음 키는 notification이 만든다)은 #116 C-1·C-4가 정해진 뒤
 * 더한다.
 *
 * @param recipientMemberId 받는 회원
 * @param roomRecordId 호실 주제면 그 호실, 계정 주제면 null
 * @param topic 주제
 * @param target 누르면 이동할 기록. 계정 주제는 null
 */
public record InfoCommand(
    UUID recipientMemberId, UUID roomRecordId, InAppTopic topic, RequestTarget target) {

  public InfoCommand {
    Objects.requireNonNull(recipientMemberId, "recipientMemberId");
    Objects.requireNonNull(topic, "topic");
    if (!topic.roomScoped() && (roomRecordId != null || target != null)) {
      throw new IllegalArgumentException("계정 알림에는 호실·대상이 없어요: " + topic);
    }
    if (topic.roomScoped() && roomRecordId == null) {
      throw new IllegalArgumentException("호실 알림에는 호실이 있어야 해요: " + topic);
    }
  }

  /** 계정 알림(호실·대상 없음). */
  public static InfoCommand account(UUID recipientMemberId, InAppTopic topic) {
    return new InfoCommand(recipientMemberId, null, topic, null);
  }
}
