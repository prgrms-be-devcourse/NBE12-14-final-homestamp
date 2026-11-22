package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.rule.SoloReason;
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
 * 응답 요청 한 건({@code state.response_request}, 05 2-4, C 테이블). 상대가 답해야 하는 기록 하나에 대한 기다림의 원본이다(기한·상태·혼자
 * 진행 이유). 통지의 전달·열람 증거는 {@link Notice}에 있다.
 *
 * <p>대상은 V1의 대상 칸 여덟 개 중 하나이거나, 모두 비어 있으면 호실 기록 자체다(예: 거주 종료 요청). 요청 종류와 대상 종류의 짝은 여기서 검사하지
 * 않는다(정해지지 않은 규칙이 있음. 예: 정산 끝내기의 대상이 호실 기록인지 정산인지, #117).
 *
 * <p>상태를 바꾸는 메서드는 아직 없다. 답함·닫힘·미확인 전이는 실제 {@code WaitService}에서 더한다.
 */
@Entity
@Table(schema = "state", name = "response_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResponseRequest {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private RequestKind kind;

  // 대상 칸. 하나만 채우거나(V1 num_nonnulls <= 1) 모두 비운다(호실 기록 자체).
  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  /** 제안이 아니라 안건(정산 항목)을 가리킨다(05 2-4). */
  @Column(name = "settlement_item_id", updatable = false)
  private UUID settlementItemId;

  @Column(name = "settlement_id", updatable = false)
  private UUID settlementId;

  @Column(name = "inspection_id", updatable = false)
  private UUID inspectionId;

  @Column(name = "inspection_item_id", updatable = false)
  private UUID inspectionItemId;

  @Column(name = "settlement_transfer_id", updatable = false)
  private UUID settlementTransferId;

  /** 상황 변화 확인이 가리키는 진행 상황. 상황 변화 확인이면 필수다(V1 CHECK). */
  @Column(name = "progress_note_id", updatable = false)
  private UUID progressNoteId;

  @Enumerated(EnumType.STRING)
  @Column(name = "requested_by_side", nullable = false, updatable = false)
  private PartySide requestedBySide;

  /** 첫 통지가 실제로 전달된 시각. 만들 때는 비어 있고 전달 증거가 생겨야 채운다(03 1-1 "타이머 시작점"). */
  @Column(name = "first_notified_at")
  private Instant firstNotifiedAt;

  @Column(name = "renotify_due_at")
  private Instant renotifyDueAt;

  @Column(name = "unconfirmed_due_at")
  private Instant unconfirmedDueAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RequestStatus status;

  /** 닫힌 이유. 상태가 닫힘일 때만 있다(V1 CHECK). */
  @Enumerated(EnumType.STRING)
  @Column(name = "closed_reason")
  private ClosedReason closedReason;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "solo_reason")
  private SoloReason soloReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private long version;

  /** 기다리는 요청을 만든다. 시각은 서버 시각(부르는 쪽의 {@code Clock}이나 행동 시각)을 받는다. */
  public ResponseRequest(
      UUID roomRecordId,
      RequestKind kind,
      RequestTarget target,
      PartySide requestedBySide,
      Instant createdAt) {
    this.roomRecordId = Objects.requireNonNull(roomRecordId, "roomRecordId");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.requestedBySide = Objects.requireNonNull(requestedBySide, "requestedBySide");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.status = RequestStatus.WAITING;
    setTarget(Objects.requireNonNull(target, "target"));
  }

  /** 저장된 대상 칸을 다시 {@link RequestTarget}으로 돌려준다. 칸이 모두 비어 있으면 호실 기록 자체다. */
  public RequestTarget target() {
    if (repairCaseId != null) {
      return RequestTarget.of(TargetKind.REPAIR_CASE, repairCaseId);
    }
    if (paymentId != null) {
      return RequestTarget.of(TargetKind.PAYMENT, paymentId);
    }
    if (settlementItemId != null) {
      return RequestTarget.of(TargetKind.SETTLEMENT_ITEM, settlementItemId);
    }
    if (settlementId != null) {
      return RequestTarget.of(TargetKind.SETTLEMENT, settlementId);
    }
    if (inspectionId != null) {
      return RequestTarget.of(TargetKind.INSPECTION, inspectionId);
    }
    if (inspectionItemId != null) {
      return RequestTarget.of(TargetKind.INSPECTION_ITEM, inspectionItemId);
    }
    if (settlementTransferId != null) {
      return RequestTarget.of(TargetKind.SETTLEMENT_TRANSFER, settlementTransferId);
    }
    if (progressNoteId != null) {
      return RequestTarget.of(TargetKind.PROGRESS_NOTE, progressNoteId);
    }
    return RequestTarget.roomRecord(roomRecordId);
  }

  /**
   * 대상을 V1 칸 하나에 넣는다. 칸이 없는 종류의 거절은 {@link RequestTargetColumns#of}가 한다. 호실 기록이면 칸을 모두 비우고, 그 ID가 이
   * 요청의 호실과 같아야 한다(다른 호실을 가리키는 요청이 칸 없이 저장되는 일을 막음).
   */
  private void setTarget(RequestTarget target) {
    RequestTargetColumns columns = RequestTargetColumns.of(target);
    if (columns.roomRecordTarget() && !target.id().equals(roomRecordId)) {
      throw new IllegalArgumentException("호실 기록 대상은 이 요청의 호실이어야 해요");
    }
    repairCaseId = columns.repairCaseId();
    paymentId = columns.paymentId();
    settlementItemId = columns.settlementItemId();
    settlementId = columns.settlementId();
    inspectionId = columns.inspectionId();
    inspectionItemId = columns.inspectionItemId();
    settlementTransferId = columns.settlementTransferId();
    progressNoteId = columns.progressNoteId();
  }
}
