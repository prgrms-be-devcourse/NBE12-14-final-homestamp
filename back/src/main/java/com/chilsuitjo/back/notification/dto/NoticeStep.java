package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * 보낸 측이 보는 통지 하나의 이력(03 K "알림 이력", 06 NOTI-4a "지난 단계는 시각과 함께"). 시각은 저장한 값 그대로이고 없으면 null이다(만들어 내지
 * 않는다).
 *
 * <p>받는 번호·자리, 업체 {@code messageId}, 묶음·전달 결과 조회 칸은 담지 않는다. 보낼 예정 시각({@code scheduled_for})도 담지
 * 않는다: 미룬 이유(밤·받는 번호 한도)와 정확한 시각을 보낸 측에 말하지 않는다(03 K "보낸 사람에게").
 *
 * @param kind 요청 통지({@code REQUEST}) 또는 재통지({@code RENOTIFY})
 * @param channel 문자 또는 서비스 안 알림
 * @param status 지금 상태
 * @param requestedAt 통지를 만든 시각({@code created_at}, "발송 요청됨")
 * @param sentAt 업체가 받은 시각
 * @param deliveredAt 전달된 시각
 * @param viewedAt 링크·알림을 연 시각
 * @param failedAt 최종 실패 시각
 */
public record NoticeStep(
    NoticeKind kind,
    NoticeChannel channel,
    NoticeStatus status,
    Instant requestedAt,
    Instant sentAt,
    Instant deliveredAt,
    Instant viewedAt,
    Instant failedAt) {

  public NoticeStep {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(requestedAt, "requestedAt");
  }
}
