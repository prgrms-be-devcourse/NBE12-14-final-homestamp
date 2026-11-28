package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.rule.InAppKind;
import com.chilsuitjo.back.notification.rule.InAppTopic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 서비스 안 알림 한 줄(05 {@code in_app_notification}). 알림함 기록이지 공식 증거의 원본이 아니다(요청 상태는 {@code
 * response_request}, 전달·열람 증거는 {@code notice}). 바뀌는 칸은 읽은 시각뿐이고 저장소의 조건부 UPDATE로만 적는다(읽음은 응답이 아니라
 * 기다림·통지는 그대로, 03 K). 30일({@code notification.ttl}) 뒤 만료된다.
 */
@Entity
@Table(schema = "state", name = "in_app_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InAppNotification {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", updatable = false)
  private UUID roomRecordId;

  @Column(name = "member_id", nullable = false, updatable = false)
  private UUID memberId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private InAppKind kind;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private InAppTopic topic;

  @Column(name = "response_request_id", updatable = false)
  private UUID responseRequestId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_kind", updatable = false)
  private TargetKind targetKind;

  @Column(name = "target_id", updatable = false)
  private UUID targetId;

  @Column(name = "read_at")
  private Instant readAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private long version;

  private InAppNotification(
      UUID memberId,
      UUID roomRecordId,
      InAppTopic topic,
      TargetKind targetKind,
      UUID targetId,
      Instant createdAt,
      Instant expiresAt) {
    this.memberId = Objects.requireNonNull(memberId, "memberId");
    this.roomRecordId = roomRecordId;
    this.topic = Objects.requireNonNull(topic, "topic");
    this.kind = topic.kind();
    this.targetKind = targetKind;
    this.targetId = targetId;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("만료는 만든 시각 뒤여야 해요");
    }
  }

  /** 주제가 있는 알림(알려 줌·서비스 안내). 응답 필요 알림은 요청 → 통지 연결(NOTI-1)에서 더한다. */
  public static InAppNotification ofTopic(
      UUID memberId,
      UUID roomRecordId,
      InAppTopic topic,
      TargetKind targetKind,
      UUID targetId,
      Instant createdAt,
      Instant expiresAt) {
    return new InAppNotification(
        memberId, roomRecordId, topic, targetKind, targetId, createdAt, expiresAt);
  }

  public boolean isRead() {
    return readAt != null;
  }
}
