package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.notification.rule.NoticeChannel;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.NoticeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * 통지 한 건({@code state.notice}, 05 2-9, C 테이블). 받는 사람 한 명에게 보내는 알림 하나이고, 문자 한 통은 아웃박스 줄이 맡는다.
 *
 * <p>번호·이름·본문은 담지 않는다. 번호는 보낼 때 {@code RecipientDirectory}로 읽는다(05 {@code outbox} "번호를 복사하지 않음").
 */
@Entity
@Table(schema = "state", name = "notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

  /** 보내고 처음 전달 결과를 물을 때까지(04 4-2 "보내고 1분·5분 뒤"). */
  static final Duration FIRST_POLL = Duration.ofMinutes(1);

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "response_request_id", updatable = false)
  private UUID responseRequestId;

  @Column(name = "link_id", updatable = false)
  private UUID linkId;

  @Column(name = "recipient_party_id", nullable = false, updatable = false)
  private UUID recipientPartyId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private NoticeKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private NoticeChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NoticeStatus status;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "viewed_at")
  private Instant viewedAt;

  @Column(name = "failed_at")
  private Instant failedAt;

  /** 솔라피 messageId. 결과 모름이면 비어 있고 전달 결과 조회가 묶음 ID로 찾는다. */
  @Column(name = "provider_message_id")
  private String providerMessageId;

  /** 같은 문자 한 통으로 나간 통지의 대표. 혼자 나가면 자기 자신. 보낼 때 채운다. */
  @Column(name = "batch_lead_id")
  private UUID batchLeadId;

  @Column(name = "next_poll_at")
  private Instant nextPollAt;

  /** 보낼 예정 시각(모으기·밤 보류·한도 미룸). */
  @Column(name = "scheduled_for")
  private Instant scheduledFor;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private long version;

  public Notice(
      UUID roomRecordId,
      UUID responseRequestId,
      UUID linkId,
      UUID recipientPartyId,
      NoticeKind kind,
      NoticeChannel channel,
      Instant scheduledFor,
      Instant createdAt) {
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "roomRecordId");
    this.responseRequestId = responseRequestId;
    this.linkId = linkId;
    this.recipientPartyId = Objects.requireNonNull(recipientPartyId, "recipientPartyId");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.status = NoticeStatus.REQUESTED;
    this.scheduledFor = scheduledFor;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** 업체가 받았다(보냄) 또는 결과를 모른다(보낸 것으로 봄, 04 4-2). 전달 결과는 1분 뒤부터 묻는다. */
  public void markSent(String providerMessageId, Instant at) {
    if (status != NoticeStatus.REQUESTED) {
      return;
    }
    this.status = NoticeStatus.SENT;
    this.sentAt = at;
    this.providerMessageId = providerMessageId;
    this.batchLeadId = id;
    this.nextPollAt = at.plus(FIRST_POLL);
  }

  /** 보내지 못했다(업체가 거절, 보낼 번호 없음, 다시 시도 끝). 보낸 측이 한 번 다시 보낼 수 있다(05 {@code notice}). */
  public void markFailed(Instant at) {
    if (status != NoticeStatus.REQUESTED) {
      return;
    }
    this.status = NoticeStatus.FAILED;
    this.failedAt = at;
  }
}
