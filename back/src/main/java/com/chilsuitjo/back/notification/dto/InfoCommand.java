package com.chilsuitjo.back.notification.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 응답이 필요 없는 서비스 안 알림 하나(05 {@code in_app_notification}, kind {@code INFO}). 읽음은 확인이 아니다(03 K).
 *
 * @param roomRecordId 호실과 상관없는 서비스 안내면 null
 * @param target 누르면 이동할 기록. 없으면 null
 * @param groupKey 하루 묶음 알림이면 키(예: {@code erase-digest:<호실>:<날짜>}), 아니면 null. 같은 키는 한 줄에 개수만 더한다
 */
public record InfoCommand(
    UUID recipientMemberId, UUID roomRecordId, RequestTarget target, String groupKey) {

  public InfoCommand {
    Objects.requireNonNull(recipientMemberId, "recipientMemberId");
  }
}
