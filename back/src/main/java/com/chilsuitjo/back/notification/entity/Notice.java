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

  @Column(name = "delivery_poll_token")
  private UUID deliveryPollToken;

  @Column(name = "delivery_poll_started_at")
  private Instant deliveryPollStartedAt;

  @Column(name = "delivery_poll_protected_until")
  private Instant deliveryPollProtectedUntil;

  /**
   * 전달 확인 기한: 결과 없이 기다리는 마지막 시각. 보낼 때({@link #markSent}) 한 번 정하고 바꾸지 않는다. 설정({@code
   * delivery.unknown-after})을 바꿔도 이미 보낸 통지의 기한은 그대로다(06 0-1). 보내기 전에는 비어 있다.
   */
  @Column(name = "delivery_check_due_at")
  private Instant deliveryCheckDueAt;

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

  /**
   * 업체가 받았다(보냄) 또는 결과를 모른다(보낸 것으로 봄, 04 4-2). 첫 조회 시각과 전달 확인 기한은 설정을 읽은 작업자가 보낸 시각 기준으로 정해 넘긴다. 기한은
   * 여기서만 정해지고 뒤의 조회·설정 변경으로 바뀌지 않는다(이미 보낸 통지는 그대로 둠).
   */
  public void markSent(
      String providerMessageId, Instant at, Instant firstPollAt, Instant deliveryCheckDueAt) {
    if (status != NoticeStatus.REQUESTED) {
      return;
    }
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(firstPollAt, "firstPollAt");
    Objects.requireNonNull(deliveryCheckDueAt, "deliveryCheckDueAt");
    if (!deliveryCheckDueAt.isAfter(at)) {
      throw new IllegalArgumentException("전달 확인 기한은 보낸 시각보다 뒤여야 해요");
    }
    if (!firstPollAt.isAfter(at)) {
      throw new IllegalArgumentException("첫 전달 결과 조회 시각은 보낸 시각보다 뒤여야 해요");
    }
    if (firstPollAt.isAfter(deliveryCheckDueAt)) {
      throw new IllegalArgumentException("첫 전달 결과 조회 시각은 전달 확인 기한을 넘을 수 없어요");
    }
    this.status = NoticeStatus.SENT;
    this.sentAt = at;
    this.providerMessageId = providerMessageId;
    this.batchLeadId = id;
    this.nextPollAt = firstPollAt;
    this.deliveryCheckDueAt = deliveryCheckDueAt;
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

  /** 조회 예약과 세대를 원자적으로 저장한다. 실제 조회 시작은 별도 트랜잭션에서 기록한다. */
  public void claimDeliveryPoll(UUID token, Instant next) {
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.deliveryPollToken = Objects.requireNonNull(token, "token");
    this.deliveryPollStartedAt = null;
    this.deliveryPollProtectedUntil = null;
    scheduleNextPoll(next);
  }

  /** 조회 batch를 DB에서 논리적으로 활성화할 때 호출한다. 전체 batch가 공유하는 논리 시작 시각을 받으며, 회수된 세대라면 조회하지 않는다. */
  public boolean startDeliveryPoll(UUID token, Instant startedAt, Instant protectedUntil) {
    if (status != NoticeStatus.SENT
        || !Objects.equals(deliveryPollToken, token)
        || deliveryPollStartedAt != null) {
      return false;
    }
    if (!protectedUntil.isAfter(startedAt)) {
      throw new IllegalArgumentException("조회 보호 종료는 시작 뒤여야 해요");
    }
    deliveryPollStartedAt = startedAt;
    deliveryPollProtectedUntil = protectedUntil;
    if (nextPollAt == null || nextPollAt.isAfter(protectedUntil)) {
      nextPollAt = protectedUntil;
    }
    return true;
  }

  public boolean ownsDeliveryPoll(UUID token, Instant now) {
    return status == NoticeStatus.SENT
        && Objects.equals(deliveryPollToken, token)
        && deliveryPollStartedAt != null
        && now.isBefore(deliveryPollProtectedUntil);
  }

  /** 기한 전에 실제 시작한 조회만 기한 만료 FAILED보다 우선한다. */
  public boolean hasProtectedDeliveryPoll(Instant now, Instant deadline) {
    return deliveryPollStartedAt != null
        && deliveryPollStartedAt.isBefore(deadline)
        && now.isBefore(deliveryPollProtectedUntil);
  }

  public void finishDeliveryPoll(UUID token, Instant scheduledNextPollAt) {
    if (!Objects.equals(deliveryPollToken, token)) {
      return;
    }
    deliveryPollToken = null;
    deliveryPollStartedAt = null;
    deliveryPollProtectedUntil = null;
    nextPollAt = Objects.requireNonNull(scheduledNextPollAt, "scheduledNextPollAt");
  }

  /**
   * 업체가 전달됐다고 답했다(결과 4000, 04 4-2). 보낸(SENT) 통지만 바뀌고, 그 밖의 상태(아직 보내기 전, 이미 전달·열람·실패)는 그대로 둔다(상태는
   * 앞으로만). 더 묻지 않으므로 다음 조회 시각을 지운다.
   *
   * @param at 서버가 업체의 DELIVERED 응답을 관측한 시각. 업체가 제공한 실제 배송 시각이나 DB 저장 완료 시각과는 다르다(#124 리뷰에서 합의, 05
   *     {@code notice.delivered_at})
   */
  public void markDelivered(Instant at) {
    Objects.requireNonNull(at, "at");
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.status = NoticeStatus.DELIVERED;
    this.deliveredAt = at;
    this.nextPollAt = null;
    deliveryPollToken = null;
    deliveryPollStartedAt = null;
    deliveryPollProtectedUntil = null;
  }

  /**
   * 보낸 문자가 전달되지 않았다고 끝났다(업체의 최종 실패 결과, 또는 전달 확인 기한({@link #deliveryCheckDueAt})까지 결과 없음, 04 4-2·05
   * {@code notice}). 보낸(SENT) 통지만 바뀌고 그 밖의 상태는 그대로 둔다. 보내기 전 실패는 {@link #markFailed}다. 더 묻지 않으므로 다음
   * 조회 시각을 지운다.
   */
  public void markDeliveryFailed(Instant at) {
    Objects.requireNonNull(at, "at");
    if (status != NoticeStatus.SENT) {
      return;
    }
    this.status = NoticeStatus.FAILED;
    this.failedAt = at;
    this.nextPollAt = null;
    deliveryPollToken = null;
    deliveryPollStartedAt = null;
    deliveryPollProtectedUntil = null;
  }
}
