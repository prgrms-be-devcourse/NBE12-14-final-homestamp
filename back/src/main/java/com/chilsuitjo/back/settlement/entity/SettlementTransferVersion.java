package com.chilsuitjo.back.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** 정산 송금 판(05 2-7). 금액이나 송금일을 고칠 때는 이전 판을 참조하는 새 행을 만든다. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    schema = "record",
    name = "settlement_transfer_version",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_settlement_transfer_version_no",
            columnNames = {"settlement_transfer_id", "no"}))
public class SettlementTransferVersion implements Persistable<UUID> {
  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "settlement_transfer_id", nullable = false, updatable = false)
  private UUID settlementTransferId;

  @Column(nullable = false, updatable = false)
  private int no;

  /** 원 단위 금액. */
  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(name = "paid_on", nullable = false, updatable = false)
  private LocalDate paidOn;

  @Column(name = "previous_id", updatable = false)
  private UUID previousId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** ID를 미리 받은 판도 Spring Data가 새 행으로 저장할 수 있게 한다. */
  @Transient
  @Getter(AccessLevel.NONE)
  private boolean newRow = true;

  public SettlementTransferVersion(
      UUID id,
      UUID roomRecordId,
      UUID settlementTransferId,
      int no,
      long amount,
      LocalDate paidOn,
      UUID previousId,
      Instant createdAt) {
    if (no < 1) {
      throw new IllegalArgumentException("판 번호는 1 이상이에요");
    }
    if (amount < 0) {
      throw new IllegalArgumentException("금액은 0 이상이에요");
    }
    this.id = Objects.requireNonNull(id, "송금 판 ID가 있어야 해요");
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "호실 기록 ID가 있어야 해요");
    this.settlementTransferId = Objects.requireNonNull(settlementTransferId, "송금 ID가 있어야 해요");
    this.no = no;
    this.amount = amount;
    this.paidOn = Objects.requireNonNull(paidOn, "송금일이 있어야 해요");
    this.previousId = previousId;
    this.createdAt = Objects.requireNonNull(createdAt, "생성 시각이 있어야 해요");
  }

  @Override
  public boolean isNew() {
    return newRow;
  }

  @PostPersist
  @PostLoad
  void markStored() {
    newRow = false;
  }
}
