package com.chilsuitjo.back.settlement.entity;

import com.chilsuitjo.back.settlement.rule.DepositStatus;
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
 * 정산({@code state.settlement}, 05 2-7, C 테이블). 호실 기록마다 하나이고, 정산을 열 때 만든다. 보증금 안건의 머리이기도 하다.
 *
 * <p>{@code snapshotNo}는 정산 모습 번호다. 항목·송금·보증금 제안은 다른 행이라 {@code version}이 오르지 않으므로, 정산이 바뀌는 행동의
 * 트랜잭션에서 따로 올린다(05 2-7). 상태를 바꾸는 메서드는 정산 기능(SET-1~5)을 만들 때 더한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement", schema = "state")
public class Settlement {
  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false, unique = true)
  private UUID roomRecordId;

  @Enumerated(EnumType.STRING)
  @Column(name = "deposit_status", nullable = false, length = 20)
  private DepositStatus depositStatus;

  /** 답 기다리는 보증금 제안. */
  @Column(name = "current_deposit_proposal_id")
  private UUID currentDepositProposalId;

  /** 지금 합의한 보증금 제안. 바꾸자는 안이 와도 상대가 동의할 때까지 그대로다. */
  @Column(name = "agreed_deposit_proposal_id")
  private UUID agreedDepositProposalId;

  @Column(name = "snapshot_no", nullable = false)
  private int snapshotNo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private Long version;

  /** 정산 열기. 처음 상태는 보증금 확인 전, 정산 모습 1번. */
  public Settlement(UUID roomRecordId, Instant createdAt) {
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "정산에는 호실 기록이 있어야 해요");
    this.createdAt = Objects.requireNonNull(createdAt, "정산에는 생성 시각이 있어야 해요");
    this.depositStatus = DepositStatus.NOT_ENTERED;
    this.snapshotNo = 1;
  }
}
