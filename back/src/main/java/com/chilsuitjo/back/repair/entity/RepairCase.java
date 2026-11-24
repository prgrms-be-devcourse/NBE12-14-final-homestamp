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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수리 건 머리({@code state.repair_case}, 05 2-5, C 테이블). 하자 인정({@code defectAck})과 수리 진행({@code
 * status})이 따로 간다(03 D).
 *
 * <p>상태 칸은 {@code RepairTransitions.apply} 결과로만 바뀐다(05 2-5). 상태를 바꾸는 메서드는 수리 기능(REP-1~5)을 만들 때
 * {@code RepairStateStore}와 함께 더한다. 다른 도메인에는 이 엔티티를 넘기지 않는다(09 2장 {@code dto/}).
 */
@Entity
@Table(schema = "state", name = "repair_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

  /**
   * 상대에게 실제로 보이게 된 시각을 남긴다(decisions 2026-11-18). 이미 있으면 그대로 둔다: 다시 공유해도 한 번 보인 기록은 다시 숨겨지지 않아서 처음
   * 시각만 뜻이 있다. 시각은 그 행동의 시각({@code ActionRequestContext.at()})이다.
   */
  public void markShown(Instant at) {
    if (firstShownAt == null) {
      firstShownAt = at;
    }
  }

  /**
   * 하자 인정을 바꾼다(응답·보충, 06 REP-2). 값은 {@code RepairTransitions.apply}가 정한 다음 상태 그대로다. 동시에 바뀌면
   * {@code @Version}이 막는다(409 {@code VERSION_CONFLICT}).
   */
  public void changeDefectAck(AckState next) {
    this.defectAck = Objects.requireNonNull(next, "next");
  }

  /**
   * 상대가 응답한 판에 도장을 찍는다(03 1-3 "[맞아요]·보류·이견 모두 도장"). 응답은 지금 판에만 할 수 있어서(본 판 번호가 같아야 함) 지금 판이 도장 찍힌
   * 판이 된다. 보충해 확인 기다림으로 돌아가도 도장은 그대로 둔다(그 모습을 봤다는 사실은 남음).
   */
  public void stampCurrent() {
    this.confirmedContentId = currentContentId;
  }
}
