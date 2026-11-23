package com.chilsuitjo.back.settlement.entity;

import com.chilsuitjo.back.settlement.rule.SettlementItemSource;
import com.chilsuitjo.back.settlement.rule.SettlementItemStatus;
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
 * 정산 항목({@code state.settlement_item}, 05 2-7, C 테이블). 퇴거 때 보증금에서 빼거나 더할 금액 하나이고, 공제 여부·금액 안건의 머리다.
 *
 * <p>출처·부위·종류는 만들 때 한 번만 쓴다. 바꾸려면 이 항목을 제외 제안하고 새 항목을 넣는다. 금액·방향은 제안 줄에, 사유는 제안에 둔다. 출처마다 채우는 칸이
 * 달라서(V1 CHECK) 생성자는 숨기고 출처마다 팩토리를 둔다. 상태를 바꾸는 메서드는 정산 기능(SET-1~5)을 만들 때 더한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_item", schema = "state")
public class SettlementItem {
  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "settlement_id", nullable = false, updatable = false)
  private UUID settlementId;

  @Enumerated(EnumType.STRING)
  @Column(length = 10, nullable = false, updatable = false)
  private SettlementItemSource source;

  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "room_part_id", updatable = false)
  private UUID roomPartId;

  @Column(length = 30, nullable = false, updatable = false)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(length = 10, nullable = false)
  private SettlementItemStatus status;

  @Column(name = "current_proposal_id")
  private UUID currentProposalId;

  @Column(name = "agreed_proposal_id")
  private UUID agreedProposalId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private Long version;

  private SettlementItem(
      UUID roomRecordId,
      UUID settlementId,
      SettlementItemSource source,
      UUID repairCaseId,
      UUID roomPartId,
      String category,
      Instant createdAt) {
    Objects.requireNonNull(roomRecordId, "정산 항목에는 호실 기록이 있어야 해요");
    Objects.requireNonNull(settlementId, "정산 항목에는 정산이 있어야 해요");
    Objects.requireNonNull(source, "정산 항목에는 출처가 있어야 해요");
    Objects.requireNonNull(createdAt, "정산 항목에는 생성 시각이 있어야 해요");
    Objects.requireNonNull(category, "정산 항목에는 종류가 있어야 해요");
    if (category.isBlank()) {
      throw new IllegalArgumentException("정산 항목의 종류를 입력해 주세요");
    }
    if (category.codePointCount(0, category.length()) > 30) {
      throw new IllegalArgumentException("정산 항목의 종류는 30자 이내로 입력해 주세요");
    }
    this.roomRecordId = roomRecordId;
    this.settlementId = settlementId;
    this.source = source;
    this.repairCaseId = repairCaseId;
    this.status = SettlementItemStatus.PENDING;
    this.roomPartId = roomPartId;
    this.category = category;
    this.createdAt = createdAt;
  }

  /** 수리 건에서 온 후보. 부위는 수리 건을 따르므로 비운다. */
  public static SettlementItem forRepairCase(
      UUID roomRecordId, UUID settlementId, UUID repairCaseId, Instant createdAt) {
    if (repairCaseId == null) {
      throw new NullPointerException("수리 건에서 온 정산 항목에는 수리 건이 있어야 해요");
    }
    return new SettlementItem(
        roomRecordId,
        settlementId,
        SettlementItemSource.REPAIR,
        repairCaseId,
        null,
        "수리 건",
        createdAt);
  }

  /** 직접 입력한 항목. 수리 건은 없고, 부위는 비울 수 있다. */
  public static SettlementItem forManual(
      UUID roomRecordId, UUID settlementId, UUID roomPartId, String category, Instant createdAt) {
    return new SettlementItem(
        roomRecordId,
        settlementId,
        SettlementItemSource.MANUAL,
        null,
        roomPartId,
        category,
        createdAt);
  }
}
