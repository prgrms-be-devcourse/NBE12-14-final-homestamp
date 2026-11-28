package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.InAppKind;
import com.chilsuitjo.back.notification.rule.InAppTopic;
import java.time.Instant;
import java.util.UUID;

/**
 * 알림 목록 한 줄({@code GET /api/me/notifications}, 06 NOTI-7). 문구는 담지 않는다: 화면이 {@code kind}·{@code
 * topic}으로 08의 문구를 고른다. 이름·사유 글도 저장하지 않는다(#116).
 *
 * @param id 알림 ID(읽음 표시에 씀)
 * @param kind 응답 필요·알려 줌·서비스 안내
 * @param topic 알려 줌·서비스 안내의 주제. 응답 필요면 null
 * @param roomRecordId 호실 알림이면 그 호실, 계정 알림이면 null
 * @param target 누르면 이동할 기록. 없으면 null
 * @param createdAt 만든 시각
 * @param read 읽었으면 true(읽음은 응답이 아님)
 */
public record InAppNotificationView(
    UUID id,
    InAppKind kind,
    InAppTopic topic,
    UUID roomRecordId,
    RequestTarget target,
    Instant createdAt,
    boolean read) {}
