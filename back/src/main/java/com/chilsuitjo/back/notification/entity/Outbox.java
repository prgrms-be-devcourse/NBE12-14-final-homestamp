package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.notification.rule.OutboxKind;
import com.chilsuitjo.back.notification.rule.OutboxStatus;
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
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아웃박스 줄({@code state.outbox}, 05 2-9, C 테이블). 행동과 같은 트랜잭션에 저장하고, 커밋된 뒤 작업자가 꺼내 보낸다(04 4-2).
 *
 * <p>문자 줄의 payload에는 통지 ID만 둔다. 번호·이름·본문을 복사하지 않는다.
 */
@Entity
@Table(schema = "state", name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

  private static final JsonMapper JSON = new JsonMapper();

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private OutboxKind kind;

  @Column(name = "dedupe_key", updatable = false)
  private String dedupeKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, updatable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OutboxStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private long version;

  private Outbox(
      OutboxKind kind, String dedupeKey, String payload, Instant nextAttemptAt, Instant createdAt) {
    this.kind = kind;
    this.dedupeKey = dedupeKey;
    this.payload = payload;
    this.status = OutboxStatus.PENDING;
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** 통지 하나를 문자로 보낼 줄. 같은 통지는 한 줄이다({@code dedupe_key = sms:<통지 ID>}). */
  public static Outbox sms(UUID noticeId, Instant sendAt, Instant now) {
    Objects.requireNonNull(noticeId, "noticeId");
    return new Outbox(
        OutboxKind.SMS, "sms:" + noticeId, "{\"noticeId\":\"" + noticeId + "\"}", sendAt, now);
  }

  /** 문자 줄이 가리키는 통지. */
  public Optional<UUID> noticeId() {
    try {
      JsonNode object = JSON.readTree(payload);
      if (object == null || !object.isObject() || object.size() != 1) {
        return Optional.empty();
      }
      JsonNode value = object.get("noticeId");
      if (value == null || !value.isString()) {
        return Optional.empty();
      }
      String text = value.asString();
      UUID id = UUID.fromString(text);
      return id.toString().equalsIgnoreCase(text) ? Optional.of(id) : Optional.empty();
    } catch (JacksonException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** 업체에 보내기 직전. 이 상태를 먼저 커밋해서 앱이 멈춰도 자동으로 두 번 보내지 않는다(04 4-2). */
  public void markSending() {
    this.status = OutboxStatus.SENDING;
    this.attempts++;
  }

  public void markDone() {
    this.status = OutboxStatus.DONE;
  }

  public void markFailed() {
    this.status = OutboxStatus.FAILED;
  }

  /**
   * 작업자가 가져가기 전에 취소했다(#100 U2, 번호를 고쳐 다시 초대). 아직 {@code PENDING}일 때만 바뀌고, 그 밖이면(이미 {@code
   * SENDING}·끝남·취소됨) 그대로 둔다. 바뀌었는지를 돌려준다. 시도 횟수·다음 시도 시각은 바꾸지 않는다. 작업자는 {@code PENDING}만 가져가서 취소한
   * 줄은 다시 보내지 않는다. 가져간 줄을 작업자가 보내기 전에 취소하는 것은 {@link #markCancelledBeforeSend}다.
   */
  public boolean markCancelled() {
    if (status != OutboxStatus.PENDING) {
      return false;
    }
    this.status = OutboxStatus.CANCELLED;
    return true;
  }

  /**
   * 이 줄을 가져간 작업자가 업체에 보내기 전에 초대 링크가 이미 무효인 것을 알고 보내지 않기로 했다(decisions 2026-11-26). {@code SENDING}일
   * 때만 바뀌고 바뀌었는지를 돌려준다. 이 시도의 시도 횟수는 그대로 둔다. 번호 고치기 쪽 취소는 이것을 쓰지 않는다({@link #markCancelled}).
   */
  public boolean markCancelledBeforeSend() {
    if (status != OutboxStatus.SENDING) {
      return false;
    }
    this.status = OutboxStatus.CANCELLED;
    return true;
  }

  /** 업체가 확실히 받지 않았을 때(연결 실패 등) 나중에 다시 꺼내게 둔다. */
  public void retryAt(Instant at) {
    this.status = OutboxStatus.PENDING;
    this.nextAttemptAt = at;
  }
}
