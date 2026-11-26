package com.chilsuitjo.back.inspection.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.LockReason;
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
 * 점검({@code state.inspection}, 05 2-6, C 테이블). 호실 기록마다 입주·퇴거 각 하나다({@code uq_inspection_kind}).
 *
 * <p>상태 칸은 {@code InspectionTransitions.apply} 결과로만 바뀐다. 다른 도메인에는 이 엔티티를 넘기지 않는다(09 2장 {@code
 * dto/}).
 */
@Entity
@Table(schema = "state", name = "inspection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private InspectionKind kind;

  /** 기준 기록을 쓰는 측(먼저 시작한 측, 03 H). */
  @Enumerated(EnumType.STRING)
  @Column(name = "author_side", nullable = false, length = 10, updatable = false)
  private PartySide authorSide;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private InspectionStatus status;

  /** 입주일보다 늦게 시작한 입주 점검(06 INSP-2a). 표시는 B가 만들어 지금은 늘 거짓이다. */
  @Column(nullable = false, updatable = false)
  private boolean late;

  /** {@code status}가 LOCKED일 때만 채운다(V1 CHECK). */
  @Column(name = "locked_at")
  private Instant lockedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_reason", length = 20)
  private LockReason lockReason;

  /** 상대에게 처음 보인 시각(확인 요청을 보낸 때). null이면 탈퇴 때 지우는 초안(05 2-10). */
  @Column(name = "first_shown_at")
  private Instant firstShownAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /** 점검 시작. 작성 중에서 시작하고 아직 상대에게 보이지 않는다(06 INSP-1a). */
  public Inspection(
      UUID roomRecordId, InspectionKind kind, PartySide authorSide, Instant createdAt) {
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "roomRecordId");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.authorSide = Objects.requireNonNull(authorSide, "authorSide");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = InspectionStatus.DRAFT;
  }
}
