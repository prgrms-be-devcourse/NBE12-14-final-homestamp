package com.chilsuitjo.back.auth.entity;

import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 휴대폰 인증 한 번({@code state.phone_verification}, 05, C 테이블). 짧게 쓰고 하루 뒤 지운다.
 *
 * <p>{@code expiresAt}은 인증 전에는 인증번호의 만료 시각이고, 인증에 성공하면 인증 표의 만료 시각(인증 시각 + {@code
 * verify.ticket.ttl})이 된다(06 ACC-1l). 같은 진행에서 다시 받으면 이전 행의 {@code expiresAt}을 그때로 당긴다. 만료와 바뀜은 칸이
 * 아니라 나중 행이 있는지로 가른다(05).
 *
 * <p>ID는 해시 입력에 들어가서 저장 전에 만든다.
 */
@Entity
@Table(schema = "state", name = "phone_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneVerification {

  @Id private UUID id;

  @Column(nullable = false, length = 20, updatable = false)
  private String phone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private VerificationPurpose purpose;

  @Column(name = "code_hash", nullable = false, length = 128, updatable = false)
  private String codeHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 저장 전에는 비어 있어 새 행으로 저장된다(ID는 미리 만든다). */
  @Version private Long version;

  public PhoneVerification(
      UUID id,
      String phone,
      VerificationPurpose purpose,
      String codeHash,
      Instant createdAt,
      Instant expiresAt) {
    this.id = Objects.requireNonNull(id);
    this.phone = Objects.requireNonNull(phone);
    this.purpose = Objects.requireNonNull(purpose);
    this.codeHash = Objects.requireNonNull(codeHash);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.expiresAt = Objects.requireNonNull(expiresAt);
  }

  /** 틀린 번호를 넣었다. */
  public void recordWrongAttempt() {
    attemptCount++;
  }

  /** 인증했다. 이 행은 이제 인증 표이고, 표는 인증한 때부터 {@code ticketExpiresAt}까지 쓴다. */
  public void markVerified(Instant now, Instant ticketExpiresAt) {
    this.verifiedAt = now;
    this.expiresAt = ticketExpiresAt;
  }
}
