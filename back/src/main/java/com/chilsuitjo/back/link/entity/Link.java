package com.chilsuitjo.back.link.entity;

import com.chilsuitjo.back.link.rule.LinkInvalidReason;
import com.chilsuitjo.back.link.rule.LinkKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 링크({@code state.link}, 05 2-2, C 테이블). 토큰 원문은 두지 않고 해시만 둔다(03 C).
 *
 * <p>처음 무효가 된 뒤에는 시각과 이유를 바꾸지 않는다(불변식 7). 다른 도메인에는 이 엔티티를 넘기지 않는다(09 2장 {@code dto/}).
 */
@Entity
@DynamicUpdate
@Table(schema = "state", name = "link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link {

  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20, updatable = false)
  private LinkKind kind;

  /** 받는 자리(05 {@code party}). */
  @Column(name = "party_id", nullable = false, updatable = false)
  private UUID partyId;

  /** 공유한 수리 건. 없으면 호실 초대 화면이거나 점검 링크. */
  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  /** 공유한 점검. */
  @Column(name = "inspection_id", updatable = false)
  private UUID inspectionId;

  @Column(name = "token_hash", nullable = false, length = 128, updatable = false)
  private String tokenHash;

  @Column(name = "issued_by_member_id", nullable = false, updatable = false)
  private UUID issuedByMemberId;

  @Column(name = "invalidated_at")
  private Instant invalidatedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "invalidated_reason", length = 30)
  private LinkInvalidReason invalidatedReason;

  @Column(name = "failed_view_count", nullable = false)
  private int failedViewCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private long version;

  public Link(
      UUID id,
      UUID roomRecordId,
      LinkKind kind,
      UUID partyId,
      UUID repairCaseId,
      UUID inspectionId,
      String tokenHash,
      UUID issuedByMemberId,
      Instant createdAt) {
    if (repairCaseId != null && inspectionId != null) {
      throw new IllegalArgumentException("링크는 기록 하나만 공유한다");
    }
    if (kind == LinkKind.SETTLEMENT_INVITE && (repairCaseId != null || inspectionId != null)) {
      throw new IllegalArgumentException("정산 초대 링크는 기록을 공유하지 않는다");
    }
    this.id = id;
    this.roomRecordId = roomRecordId;
    this.kind = kind;
    this.partyId = partyId;
    this.repairCaseId = repairCaseId;
    this.inspectionId = inspectionId;
    this.tokenHash = tokenHash;
    this.issuedByMemberId = issuedByMemberId;
    this.createdAt = createdAt;
  }

  public boolean isInvalidated() {
    return invalidatedAt != null;
  }

  /** 링크를 무효로 한다(03 C). 처음 무효가 된 시각과 이유는 바꾸지 않는다(불변식 7). 이미 무효면 그대로 둔다. */
  public void invalidate(LinkInvalidReason reason, Instant at) {
    if (isInvalidated()) {
      return;
    }
    this.invalidatedReason = reason;
    this.invalidatedAt = at;
  }
}
