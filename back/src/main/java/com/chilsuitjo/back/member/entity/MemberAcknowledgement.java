package com.chilsuitjo.back.member.entity;

import com.chilsuitjo.back.member.rule.AcknowledgementKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * 약관·안내·만 14세 확인 기록 한 줄({@code record.member_acknowledgement}, 05 2-1, B 테이블). 덧붙이기만 한다: {@code
 * Immutable} + DB 트리거. 탈퇴할 때만 전용 함수({@code record.purge_member_acknowledgements})가 지운다(05 0장).
 */
@Entity
@Immutable
@Table(schema = "record", name = "member_acknowledgement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAcknowledgement {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "member_id", nullable = false, updatable = false)
  private UUID memberId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20, updatable = false)
  private AcknowledgementKind kind;

  /** 확인한 문구의 판(예: {@code 2026-11-12}, 05 2-1). */
  @Column(name = "text_version", nullable = false, length = 20, updatable = false)
  private String textVersion;

  @Column(name = "agreed_at", nullable = false, updatable = false)
  private Instant agreedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public MemberAcknowledgement(
      UUID memberId,
      AcknowledgementKind kind,
      String textVersion,
      Instant agreedAt,
      Instant createdAt) {
    this.memberId = Objects.requireNonNull(memberId);
    this.kind = Objects.requireNonNull(kind);
    this.textVersion = Objects.requireNonNull(textVersion);
    this.agreedAt = Objects.requireNonNull(agreedAt);
    this.createdAt = Objects.requireNonNull(createdAt);
  }
}
