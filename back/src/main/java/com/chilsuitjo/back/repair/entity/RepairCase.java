package com.chilsuitjo.back.repair.entity;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.MoneyStatus;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 수리 건 머리({@code state.repair_case}, 05 2-5, C 테이블). 하자 인정({@code defectAck})과 수리 진행({@code
 * status})이 따로 간다(03 D).
 *
 * <p>상태 칸은 {@code RepairTransitions.apply} 결과로만 바뀐다(05 2-5). 상태를 바꾸는 메서드는 수리 기능(REP-1~5)을 만들 때
 * {@code RepairStateStore}와 함께 더한다. 다른 도메인에는 이 엔티티를 넘기지 않는다(09 2장 {@code dto/}).
 */
@Entity
@Table(schema = "state", name = "repair_case")
public class RepairCase {

  /** 앱이 저장 전에 만든다({@code Uuids.v7()}). 첫 판과 서로를 가리켜서다(05 0장 {@code id}). */
  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 지금 판({@code repair_content}). 지연 FK라 같은 트랜잭션 안에서 판과 함께 저장한다. */
  @Column(name = "current_content_id", nullable = false)
  private UUID currentContentId;

  /** 같이 확인한 판(도장). 없으면 null. */
  @Column(name = "confirmed_content_id")
  private UUID confirmedContentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RepairStatus status;

  /** {@code status}가 DONE일 때만 채운다(V1 CHECK). */
  @Enumerated(EnumType.STRING)
  @Column(name = "closed_by", length = 10)
  private ClosedBy closedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "defect_ack", nullable = false, length = 20)
  private AckState defectAck;

  @Enumerated(EnumType.STRING)
  @Column(name = "money_status", nullable = false, length = 20)
  private MoneyStatus moneyStatus;

  /** 답 기다리는 제안(C {@code proposal}). */
  @Column(name = "current_proposal_id")
  private UUID currentProposalId;

  @Column(name = "agreed_proposal_id")
  private UUID agreedProposalId;

  /** 재발이면 이전 수리 건. */
  @Column(name = "previous_case_id", updatable = false)
  private UUID previousCaseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "created_by_side", nullable = false, length = 10, updatable = false)
  private PartySide createdBySide;

  @Column(nullable = false)
  private boolean solo;

  /** 상대에게 처음 보인 시각. null이면 탈퇴 때 지우는 초안(05 2-10). */
  @Column(name = "first_shown_at")
  private Instant firstShownAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  protected RepairCase() {}

  /**
   * 하자 등록. 처음 상태는 시작 전·확인 기다림·부담 미정. {@code id}와 {@code currentContentId}는 첫 판과 함께 {@code
   * Uuids.v7()}로 미리 만든다. {@code @Version}이 비어 있어 저장할 때 새 행으로 들어간다.
   */
  public RepairCase(
      UUID id,
      UUID roomRecordId,
      UUID currentContentId,
      PartySide createdBySide,
      UUID previousCaseId,
      Instant createdAt) {
    this.id = id;
    this.roomRecordId = roomRecordId;
    this.currentContentId = currentContentId;
    this.createdBySide = createdBySide;
    this.previousCaseId = previousCaseId;
    this.createdAt = createdAt;
    this.status = RepairStatus.NOT_STARTED;
    this.defectAck = AckState.PENDING;
    this.moneyStatus = MoneyStatus.UNDECIDED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomRecordId() {
    return roomRecordId;
  }

  public UUID getCurrentContentId() {
    return currentContentId;
  }

  public UUID getConfirmedContentId() {
    return confirmedContentId;
  }

  public RepairStatus getStatus() {
    return status;
  }

  public ClosedBy getClosedBy() {
    return closedBy;
  }

  public AckState getDefectAck() {
    return defectAck;
  }

  public MoneyStatus getMoneyStatus() {
    return moneyStatus;
  }

  public UUID getCurrentProposalId() {
    return currentProposalId;
  }

  public UUID getAgreedProposalId() {
    return agreedProposalId;
  }

  public UUID getPreviousCaseId() {
    return previousCaseId;
  }

  public PartySide getCreatedBySide() {
    return createdBySide;
  }

  public boolean isSolo() {
    return solo;
  }

  public Instant getFirstShownAt() {
    return firstShownAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getVersion() {
    return version;
  }
}
