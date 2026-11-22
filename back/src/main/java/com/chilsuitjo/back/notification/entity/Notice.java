package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.notification.rule.DeliveryPollSchedule;
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
  static final Duration FIRST_POLL = DeliveryPollSchedule.FIRST_POLL;

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

  /** 최종 실패(업체가 거절, 업체에 닿지 못한 채 3일). 보낸 측이 한 번 다시 보낼 수 있다(05 {@code notice}, 03 1-1). */
  public void markFailed(Instant at) {
    if (status != NoticeStatus.REQUESTED) {
      return;
    }
    this.status = NoticeStatus.FAILED;
    this.failedAt = at;
  }

  /**
   * 전달 결과 조회 대상으로 가져가면서 다음에 물을 시각을 옮긴다(#120). 잠근 트랜잭션 안에서 부르고, 커밋하면 다른 작업자는 그 시각까지 이 통지를 가져가지 않는다.
   * 보낸(SENT) 통지만 바뀌고, 이미 끝난 통지는 그대로 둔다.
   */
  public void scheduleNextPoll(Instant at) {
    Objects.requireNonNull(at, "at");
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.nextPollAt = at;
  }

  /**
   * 업체가 전달됐다고 답했다(결과 4000, 04 4-2). 보낸(SENT) 통지만 바뀌고, 그 밖의 상태(아직 보내기 전, 이미 전달·열람·실패)는 그대로 둔다(상태는
   * 앞으로만). 더 묻지 않으므로 다음 조회 시각을 지운다.
   *
   * @param at 전달로 기록할 시각. 서버가 결과를 확인한 시각인지 업체가 알려 준 시각인지는 정해지지 않아(#120 결정 후보) 부르는 쪽이 넘긴다
   */
  public void markDelivered(Instant at) {
    Objects.requireNonNull(at, "at");
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.status = NoticeStatus.DELIVERED;
    this.deliveredAt = at;
    this.nextPollAt = null;
  }

  /**
   * 보낸 문자가 전달되지 않았다고 끝났다(업체의 최종 실패 결과, 또는 보내고 3일 동안 결과 없음, 04 4-2·05 {@code notice}). 보낸(SENT) 통지만
   * 바뀌고 그 밖의 상태는 그대로 둔다. 보내기 전 실패는 {@link #markFailed}다. 더 묻지 않으므로 다음 조회 시각을 지운다.
   */
  public void markDeliveryFailed(Instant at) {
    Objects.requireNonNull(at, "at");
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.status = NoticeStatus.FAILED;
    this.failedAt = at;
    this.nextPollAt = null;
  }
}
