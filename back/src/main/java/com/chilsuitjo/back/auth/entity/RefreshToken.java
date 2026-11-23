package com.chilsuitjo.back.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 리프레시 토큰 하나({@code state.refresh_token}, 05 2-1, C 테이블). 토큰 원문은 저장하지 않고 해시만 둔다(04 4-1).
 *
 * <p>한 번 로그인한 기기의 토큰들이 한 묶음({@code familyId})이다. 재발급하면 이 행을 폐기하고({@code revokedAt}) 새 행을
 * 가리킨다({@code replacedById}). 폐기 뒤 유예 시간 안에 같은 토큰이 다시 오면 그 새 행의 토큰을 다시 돌려주고, 유예가 지나면 묶음 전체를
 * 폐기한다(불변식 12, 06 ACC-2b·2c). 로그아웃이나 묶음 폐기로 지운 행은 {@code replacedById}가 비어 있어 유예를 받지 않는다.
 */
@Entity
@Table(schema = "state", name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "member_id", nullable = false, updatable = false)
  private UUID memberId;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "token_hash", nullable = false, length = 128, updatable = false)
  private String tokenHash;

  /** 기기 이름. 채울 출처를 정하지 않아 비워 둔다. */
  @Column(name = "device_label", length = 100)
  private String deviceLabel;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_id")
  private UUID replacedById;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  public RefreshToken(
      UUID memberId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
    this.memberId = Objects.requireNonNull(memberId);
    this.familyId = Objects.requireNonNull(familyId);
    this.tokenHash = Objects.requireNonNull(tokenHash);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.expiresAt = Objects.requireNonNull(expiresAt);
  }

  /** 이 시각에 아직 쓸 수 있는가(폐기되지 않았고 만료 전). */
  public boolean isUsableAt(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** 재발급으로 폐기하고 새 행을 가리킨다. 이미 폐기된 행은 바꾸지 않는다. */
  public void replaceWith(UUID nextId, Instant now) {
    if (revokedAt != null) {
      throw new IllegalStateException("이미 폐기된 리프레시 토큰이다");
    }
    this.revokedAt = Objects.requireNonNull(now);
    this.replacedById = Objects.requireNonNull(nextId);
  }
}
