package com.chilsuitjo.back.repair.entity;

import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * 수리 건 내용의 판({@code record.repair_content}, 05 2-5, B 테이블). 고치면 이전 판을 가리키는 새 판을 만든다. 고칠 수 없다:
 * {@code @Immutable} + DB 트리거.
 *
 * <p>{@code UNIQUE(repair_case_id, no)}: 한 수리 건 안에서 판 번호는 1, 2, 3 …. 지금 판·도장 판은 머리({@link
 * RepairCase})가 가리킨다.
 *
 * <p>{@code id}는 앱이 저장 전에 만든다({@code Uuids.v7()}, 05 0장 {@code id}). 버전 칸이 없어 Spring Data가 새 행인지 알
 * 수 없으므로 {@link Persistable}로 알린다(merge로 가서 SELECT가 먼저 나가지 않게).
 */
@Entity
@Immutable
@Table(schema = "record", name = "repair_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairContent implements Persistable<UUID> {

  @Id private UUID id;

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

  /** 저장하거나 읽어 온 뒤에는 false. */
  @Transient
  @Getter(AccessLevel.NONE)
  private boolean newRow = true;

  public RepairContent(
      UUID id,
      UUID roomRecordId,
      UUID repairCaseId,
      int no,
      UUID roomPartId,
      String title,
      String description,
      UUID previousId,
      PartySide createdBySide,
      Instant createdAt) {
    this.id = id;
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
