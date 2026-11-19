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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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

  private static final Pattern NOTICE_ID =
      Pattern.compile("\"noticeId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

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
    Matcher matcher = NOTICE_ID.matcher(payload);
    return matcher.find() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
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

  /** 업체가 확실히 받지 않았을 때(연결 실패 등) 나중에 다시 꺼내게 둔다. */
  public void retryAt(Instant at) {
    this.status = OutboxStatus.PENDING;
    this.nextAttemptAt = at;
  }
}
