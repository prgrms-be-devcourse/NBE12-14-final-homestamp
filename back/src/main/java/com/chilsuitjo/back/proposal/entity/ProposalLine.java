package com.chilsuitjo.back.proposal.entity;

import com.chilsuitjo.back.proposal.rule.LineKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * 제안 줄({@code record.proposal_line}, 05 2-4, B 테이블). 제안 하나의 금액을 종류별로 한 줄씩 둔다. 덧붙이기만 한다:
 * {@code @Immutable} + DB 트리거.
 *
 * <p>비율 칸은 없고 금액만 둔다. 부담 줄의 합이 전체 금액과 같은지는 서비스가 검사한다(05 2-4, 06 MONEY-3a {@code SUM_MISMATCH}).
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "record", name = "proposal_line")
public class ProposalLine {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "proposal_id", nullable = false, updatable = false)
  private UUID proposalId;

  @Enumerated(EnumType.STRING)
  @Column(name = "line_kind", nullable = false, length = 20, updatable = false)
  private LineKind lineKind;

  /** 제3자 줄의 관계 설명(예: 윗집, 06 MONEY-1b). THIRD_PARTY 줄에만 쓴다(V1 CHECK). */
  @Column(name = "third_party_label", length = 30, updatable = false)
  private String thirdPartyLabel;

  /** 원 단위 금액. 0 이상(V1 CHECK). */
  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public ProposalLine(
      UUID roomRecordId,
      UUID proposalId,
      LineKind lineKind,
      String thirdPartyLabel,
      long amount,
      Instant createdAt) {
    if (amount < 0) {
      throw new IllegalArgumentException("금액은 0 이상이에요");
    }
    if (lineKind != LineKind.THIRD_PARTY && thirdPartyLabel != null) {
      throw new IllegalArgumentException("관계 설명은 제3자 줄에만 적을 수 있어요");
    }
    this.roomRecordId = roomRecordId;
    this.proposalId = proposalId;
    this.lineKind = lineKind;
    this.thirdPartyLabel = thirdPartyLabel;
    this.amount = amount;
    this.createdAt = createdAt;
  }
}
