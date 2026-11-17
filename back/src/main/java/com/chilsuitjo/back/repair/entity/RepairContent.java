package com.chilsuitjo.back.repair.entity;

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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * 수리 건 내용의 판({@code record.repair_content}, 05 2-5, B 테이블). 고치면 이전 판을 가리키는 새 판을 만든다. 고칠 수 없다:
 * {@code @Immutable} + DB 트리거.
 *
 * <p>{@code UNIQUE(repair_case_id, no)}: 한 수리 건 안에서 판 번호는 1, 2, 3 …. 지금 판·도장 판은 머리({@link
 * RepairCase})가 가리킨다.
 */
@Entity
@Immutable
@Table(schema = "record", name = "repair_content")
public class RepairContent {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", nullable = false, updatable = false)
  private UUID repairCaseId;

  /** 판 번호(1부터). 확인할 때 본 판 번호({@code seenVersionNo})가 이 값이다. */
  @Column(nullable = false, updatable = false)
  private int no;

  /** 호실 부위(A {@code room_part}). */
  @Column(name = "room_part_id", nullable = false, updatable = false)
  private UUID roomPartId;

  @Column(nullable = false, length = 100, updatable = false)
  private String title;

  @Column(updatable = false)
  private String description;

  /** 이전 판. 첫 판이면 null. */
  @Column(name = "previous_id", updatable = false)
  private UUID previousId;

  @Enumerated(EnumType.STRING)
  @Column(name = "created_by_side", nullable = false, length = 10, updatable = false)
  private PartySide createdBySide;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RepairContent() {}

  public RepairContent(
      UUID roomRecordId,
      UUID repairCaseId,
      int no,
      UUID roomPartId,
      String title,
      String description,
      UUID previousId,
      PartySide createdBySide,
      Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.no = no;
    this.roomPartId = roomPartId;
    this.title = title;
    this.description = description;
    this.previousId = previousId;
    this.createdBySide = createdBySide;
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

  public int getNo() {
    return no;
  }

  public UUID getRoomPartId() {
    return roomPartId;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public UUID getPreviousId() {
    return previousId;
  }

  public PartySide getCreatedBySide() {
    return createdBySide;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
