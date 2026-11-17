package com.chilsuitjo.back.repair.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * 원인 설명({@code record.cause_note}, 05 2-5, B 테이블). 고치면(문장이든 근거 종류든) 언제나 이전 설명을 가리키는 새 행이다(06
 * REP-8c). 같은 이전 설명의 다음 수정본은 하나뿐이다(V1 {@code ux_cause_note_previous}).
 */
@Entity
@Immutable
@Table(schema = "record", name = "cause_note")
public class CauseNote {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", nullable = false, updatable = false)
  private UUID repairCaseId;

  /** 설명을 남긴 측. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private PartySide side;

  @Column(nullable = false, updatable = false)
  private String text;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private CauseBasis basis;

  /** 근거가 EXPERT일 때 전문가 이름. */
  @Column(name = "expert_name", length = 50, updatable = false)
  private String expertName;

  @Column(name = "previous_id", updatable = false)
  private UUID previousId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CauseNote() {}

  public CauseNote(
      UUID roomRecordId,
      UUID repairCaseId,
      PartySide side,
      String text,
      CauseBasis basis,
      String expertName,
      UUID previousId,
      Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.side = side;
    this.text = text;
    this.basis = basis;
    this.expertName = expertName;
    this.previousId = previousId;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomRecordId() {
    return roomRecordId;
  }

  public UUID getRepairCaseId() {
    return repairCaseId;
  }

  public PartySide getSide() {
    return side;
  }

  public String getText() {
    return text;
  }

  public CauseBasis getBasis() {
    return basis;
  }

  public String getExpertName() {
    return expertName;
  }

  public UUID getPreviousId() {
    return previousId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
