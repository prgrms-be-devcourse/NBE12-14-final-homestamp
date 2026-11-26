package com.chilsuitjo.back.inspection.entity;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
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
 * 점검 항목 머리({@code state.inspection_item}, 05 2-6, C 테이블). 사용자가 적은 하자 하나다(03 H "항목"). 내용(부위·설명)은
 * 판({@link InspectionItemVersion})에 있다.
 *
 * <p>상태 칸은 {@code InspectionItemTransitions.apply} 결과로만 바뀐다.
 */
@Entity
@Table(schema = "state", name = "inspection_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionItem {

  /** 앱이 저장 전에 만든다({@code Uuids.v7()}). 첫 판과 서로를 가리켜서다(05 0장 {@code id}). */
  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "inspection_id", nullable = false, updatable = false)
  private UUID inspectionId;

  /** 지금 판. 지연 FK라 같은 트랜잭션 안에서 판과 함께 저장한다. */
  @Column(name = "current_version_id", nullable = false)
  private UUID currentVersionId;

  /** 같이 확인한 판(도장). 없으면 null. */
  @Column(name = "confirmed_version_id")
  private UUID confirmedVersionId;

  /** 항목 응답. 수리 건 하자 인정과 같은 값이다(05 2-6). */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AckState status;

  @Enumerated(EnumType.STRING)
  @Column(name = "added_by_side", nullable = false, length = 10, updatable = false)
  private PartySide addedBySide;

  /** 잠긴 뒤 추가(06 INSP-1e). 서버가 정한다. */
  @Column(name = "added_after_lock", nullable = false, updatable = false)
  private boolean addedAfterLock;

  /** 거주 종료 뒤 추가(06 INSP-3a). 서버가 정한다. */
  @Column(name = "added_after_end", nullable = false, updatable = false)
  private boolean addedAfterEnd;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /**
   * 항목 더하기. 확인 기다림에서 시작한다. {@code id}와 {@code currentVersionId}는 첫 판과 함께 {@code Uuids.v7()}로 미리
   * 만든다. {@code @Version}이 비어 있어 저장할 때 새 행으로 들어간다.
   */
  public InspectionItem(
      UUID id,
      UUID roomRecordId,
      UUID inspectionId,
      UUID currentVersionId,
      PartySide addedBySide,
      boolean addedAfterLock,
      boolean addedAfterEnd,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "roomRecordId");
    this.inspectionId = Objects.requireNonNull(inspectionId, "inspectionId");
    this.currentVersionId = Objects.requireNonNull(currentVersionId, "currentVersionId");
    this.addedBySide = Objects.requireNonNull(addedBySide, "addedBySide");
    this.addedAfterLock = addedAfterLock;
    this.addedAfterEnd = addedAfterEnd;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = AckState.PENDING;
  }

  /** 항목 응답을 바꾼다(맞아요·보류·이견, 보충하면 확인 기다림). 규칙은 {@code InspectionItemTransitions}가 먼저 본다. */
  public void changeStatus(AckState next) {
    this.status = Objects.requireNonNull(next, "next");
  }

  /** 지금 판에 도장을 찍는다(03 1-3 "[맞아요]·보류·이견 모두 도장"). 수리 건의 {@code RepairCase.stampCurrent}와 같다. */
  public void stampCurrent() {
    this.confirmedVersionId = currentVersionId;
  }
}
