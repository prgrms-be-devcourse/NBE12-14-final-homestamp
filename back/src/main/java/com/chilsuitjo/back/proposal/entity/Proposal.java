package com.chilsuitjo.back.proposal.entity;

import com.chilsuitjo.back.global.common.PartySide;
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
 * 제안({@code record.proposal}, 05 2-4, B 테이블). 돈 안건(수리 건 부담·정산 항목·보증금) 하나에 대한 안이다. 덧붙이기만 한다:
 * {@code @Immutable} + DB 트리거.
 *
 * <p>대상 칸은 하나만 채운다(V1 {@code num_nonnulls(...) = 1}). 그래서 생성자는 숨기고 대상마다 팩토리를 둔다. 어느 안이 기다리는 중인지는 이
 * 행이 아니라 안건 머리(C)의 {@code current_*_id}가 정한다.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "record", name = "proposal")
public class Proposal {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 수리 건 부담 제안이면 그 수리 건. */
  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  /** 정산 항목 금액·제외 제안이면 그 정산 항목. */
  @Column(name = "settlement_item_id", updatable = false)
  private UUID settlementItemId;

  /** 보증금 제안이면 그 정산. */
  @Column(name = "settlement_id", updatable = false)
  private UUID settlementId;

  @Enumerated(EnumType.STRING)
  @Column(name = "proposed_by_side", nullable = false, length = 10, updatable = false)
  private PartySide proposedBySide;

  /** 역제안이면 상대의 이전 제안. */
  @Column(name = "counter_of_id", updatable = false)
  private UUID counterOfId;

  /** 답 전에 [바꾸기]로 바꾼 내 이전 제안. */
  @Column(name = "replaces_id", updatable = false)
  private UUID replacesId;

  @Column(nullable = false, updatable = false)
  private int round;

  /** 비용 없음 제안. 수리 건 부담에만 쓴다(03 E). */
  @Column(name = "no_cost", nullable = false, updatable = false)
  private boolean noCost;

  /** 정산 항목을 빼자는 제안. 정산 항목에만 쓴다(V1 CHECK). */
  @Column(nullable = false, updatable = false)
  private boolean exclude;

  /**
   * 정산 항목 [바꾸기]·[빼기]의 사유(06 SET-2). 수리 건 부담·보증금 제안은 비운다.
   *
   * <p>이견·역제안의 사유는 이 칸이 아니라 행동 기록의 {@code reason}에 둔다. 금액·방향은 제안 줄에 둔다.
   */
  @Column(updatable = false)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private Proposal(
      UUID roomRecordId,
      UUID repairCaseId,
      UUID settlementItemId,
      UUID settlementId,
      PartySide proposedBySide,
      UUID counterOfId,
      UUID replacesId,
      int round,
      boolean noCost,
      boolean exclude,
      String reason,
      Instant createdAt) {
    if (round < 1) {
      throw new IllegalArgumentException("round는 1 이상이에요");
    }
    if (exclude && settlementItemId == null) {
      throw new IllegalArgumentException("제외(exclude) 제안은 정산 항목에만 할 수 있어요");
    }
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.settlementItemId = settlementItemId;
    this.settlementId = settlementId;
    this.proposedBySide = proposedBySide;
    this.counterOfId = counterOfId;
    this.replacesId = replacesId;
    this.round = round;
    this.noCost = noCost;
    this.exclude = exclude;
    this.reason = reason;
    this.createdAt = createdAt;
  }

  /** 수리 건 부담 제안. 사유·빼기는 없다. */
  public static Proposal forRepairCase(
      UUID roomRecordId,
      UUID repairCaseId,
      PartySide proposedBySide,
      UUID counterOfId,
      UUID replacesId,
      int round,
      boolean noCost,
      Instant createdAt) {
    return new Proposal(
        roomRecordId,
        repairCaseId,
        null,
        null,
        proposedBySide,
        counterOfId,
        replacesId,
        round,
        noCost,
        false,
        null,
        createdAt);
  }

  /** 정산 항목 금액·제외 제안. 비용 없음은 없다. */
  public static Proposal forSettlementItem(
      UUID roomRecordId,
      UUID settlementItemId,
      PartySide proposedBySide,
      UUID counterOfId,
      UUID replacesId,
      int round,
      boolean exclude,
      String reason,
      Instant createdAt) {
    return new Proposal(
        roomRecordId,
        null,
        settlementItemId,
        null,
        proposedBySide,
        counterOfId,
        replacesId,
        round,
        false,
        exclude,
        reason,
        createdAt);
  }

  /** 보증금 제안. 비용 없음·빼기·사유는 없다. */
  public static Proposal forDeposit(
      UUID roomRecordId,
      UUID settlementId,
      PartySide proposedBySide,
      UUID counterOfId,
      UUID replacesId,
      int round,
      Instant createdAt) {
    return new Proposal(
        roomRecordId,
        null,
        null,
        settlementId,
        proposedBySide,
        counterOfId,
        replacesId,
        round,
        false,
        false,
        null,
        createdAt);
  }
}
