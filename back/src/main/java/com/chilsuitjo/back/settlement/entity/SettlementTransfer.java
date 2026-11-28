package com.chilsuitjo.back.settlement.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.settlement.rule.TransferDirection;
import com.chilsuitjo.back.settlement.rule.TransferResponse;
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

/** 정산 송금 머리(05 2-7). 현재 판과 상대가 확인한 판을 가리킨다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "state", name = "settlement_transfer")
public class SettlementTransfer {
  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "settlement_id", nullable = false, updatable = false)
  private UUID settlementId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private TransferDirection direction;

  @Column(name = "current_version_id", nullable = false)
  private UUID currentVersionId;

  @Column(name = "confirmed_version_id")
  private UUID confirmedVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "recorded_by_side", nullable = false, length = 10, updatable = false)
  private PartySide recordedBySide;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TransferResponse response;

  /** 받은 측이 별도로 기록한 금액이 같은 송금이면 보낸 측의 기록을 가리킨다. */
  @Column(name = "same_as_transfer_id", updatable = false)
  private UUID sameAsTransferId;

  @Column(name = "receipt_confirmed_at")
  private Instant receiptConfirmedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /**
   * 새 송금의 ID와 첫 판 ID는 호출자가 {@code Uuids.v7()}로 미리 만든다. 현재 판의 지연 FK를 만족하도록 머리를 먼저 저장하고 같은 트랜잭션에서 첫
   * 판을 저장한다.
   */
  public SettlementTransfer(
      UUID id,
      UUID roomRecordId,
      UUID settlementId,
      TransferDirection direction,
      UUID currentVersionId,
      PartySide recordedBySide,
      UUID sameAsTransferId,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "송금 ID가 있어야 해요");
    if (id.equals(sameAsTransferId)) {
      throw new IllegalArgumentException("자기 자신을 같은 송금으로 연결할 수 없어요");
    }
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "호실 기록 ID가 있어야 해요");
    this.settlementId = Objects.requireNonNull(settlementId, "정산 ID가 있어야 해요");
    this.direction = Objects.requireNonNull(direction, "송금 방향이 있어야 해요");
    this.currentVersionId = Objects.requireNonNull(currentVersionId, "현재 판 ID가 있어야 해요");
    this.recordedBySide = Objects.requireNonNull(recordedBySide, "송금을 기록한 측이 있어야 해요");
    this.response = TransferResponse.PENDING;
    this.sameAsTransferId = sameAsTransferId;
    this.createdAt = Objects.requireNonNull(createdAt, "생성 시각이 있어야 해요");
  }

  /** 도장 전 고치기: 새 판을 지금 판으로 한다(03 J "수령 확인 전이면 그냥 고쳐짐", 06 SET-4c). */
  public void moveCurrent(UUID versionId) {
    this.currentVersionId = Objects.requireNonNull(versionId, "판 ID가 있어야 해요");
  }

  /** 도장 뒤 상대가 고친 내용을 확인: 새 판을 지금 판·도장 판으로 한다(06 SET-4d). */
  public void moveCurrentAndConfirmed(UUID versionId) {
    this.currentVersionId = Objects.requireNonNull(versionId, "판 ID가 있어야 해요");
    this.confirmedVersionId = versionId;
  }

  /**
   * 기록을 남기지 않은 측의 응답(06 4-8 {@code respond}). 맞아요면 지금 판에 도장을 찍고 수령 확인 시각을 남긴다(송금 합계는 도장 찍힌 판만, 05
   * 2-7). 맞아요는 되돌리지 않는다(규칙은 {@code TransferTransitions}).
   */
  public void answer(TransferResponse next, Instant at) {
    this.response = Objects.requireNonNull(next, "응답이 있어야 해요");
    if (next == TransferResponse.ACK) {
      this.confirmedVersionId = currentVersionId;
      this.receiptConfirmedAt = Objects.requireNonNull(at, "확인 시각이 있어야 해요");
    }
  }
}
