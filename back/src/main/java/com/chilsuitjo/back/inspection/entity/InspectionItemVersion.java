package com.chilsuitjo.back.inspection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * 점검 항목의 판({@code record.inspection_item_version}, 05 2-6, B 테이블). 고치면 이전 판을 가리키는 새 판을 만든다. 고칠 수
 * 없다: {@code @Immutable} + DB 트리거.
 *
 * <p>{@code id}는 앱이 저장 전에 만든다. 버전 칸이 없어 Spring Data가 새 행인지 알 수 없으므로 {@link Persistable}로 알린다.
 */
@Entity
@Immutable
@Table(schema = "record", name = "inspection_item_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionItemVersion implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "inspection_item_id", nullable = false, updatable = false)
  private UUID inspectionItemId;

  /** 판 번호(1부터). 응답할 때 본 판 번호({@code seenVersionNo})가 이 값이다. */
  @Column(nullable = false, updatable = false)
  private int no;

  /** 호실 부위(A {@code room_part}). */
  @Column(name = "room_part_id", nullable = false, updatable = false)
  private UUID roomPartId;

  @Column(nullable = false, updatable = false)
  private String description;

  /** 이전 판. 첫 판이면 null. */
  @Column(name = "previous_id", updatable = false)
  private UUID previousId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 저장하거나 읽어 온 뒤에는 false. */
  @Transient
  @Getter(AccessLevel.NONE)
  private boolean newRow = true;

  public InspectionItemVersion(
      UUID id,
      UUID roomRecordId,
      UUID inspectionItemId,
      int no,
      UUID roomPartId,
      String description,
      UUID previousId,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "roomRecordId");
    this.inspectionItemId = Objects.requireNonNull(inspectionItemId, "inspectionItemId");
    this.no = no;
    this.roomPartId = Objects.requireNonNull(roomPartId, "roomPartId");
    this.description = Objects.requireNonNull(description, "description");
    this.previousId = previousId;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
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
