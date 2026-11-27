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
 * <p>상태는 열린 요청(기다림·미확인)에서만 바뀐다: 답함({@link #answer(PartySide)})·거둠({@link
 * #withdraw(PartySide)})·닫힘({@link #close})·제안 바꾸기로 다시 기다림({@link #rewait}). 끝난 요청(답함·거둠·닫힘)은 다시 열지
 * 않는다(03 1-4 "닫힌 요청을 자동으로 되살리지 않는다"). 미확인으로 바꾸기·기한 정하기는 기다림 타이머(d3)와 전달 확인 연동에서 더한다. 어떤 요청을
 * 바꿀지(대상·종류·방향)는 {@code WaitService}가 고른다.
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

  /** 열린 요청인가(기다림·미확인). */
  public boolean isOpen() {
    return status.isOpen();
  }

  /**
   * 상대가 답했다. 요청한 측이 부르면 답이 아니라 거절한다. 기한 칸은 그대로 둔다(작업자는 {@code WAITING}만 보므로 재통지·미확인이 다시 걸리지 않음).
   * 미확인 뒤 늦은 답이어도 {@code solo_reason}은 지우지 않는다(혼자 진행이 열렸던 사실. 혼자 진행 판단은 상태를 먼저 본다).
   *
   * <p>답이 없는 요청·끝난 요청에 "아무것도 하지 않음"({@code WaitService#answered})은 고르는 쪽({@code WaitService})이 지킨다.
   * 여기 온 요청은 열려 있어야 한다.
   */
  public void answer(PartySide answeredBySide) {
    requireOpen();
    if (answeredBySide == requestedBySide) {
      throw new IllegalStateException("요청한 측은 자기 요청에 답하지 않아요");
    }
    status = RequestStatus.ANSWERED;
  }

  /** 요청한 측이 거뒀다({@code WITHDRAWN}, decisions 2026-11-26). 요청한 측만 거둘 수 있다. */
  public void withdraw(PartySide withdrawnBySide) {
    requireOpen();
    if (withdrawnBySide != requestedBySide) {
      throw new IllegalStateException("요청한 측만 거둘 수 있어요");
    }
    status = RequestStatus.WITHDRAWN;
  }

  /** 답 없이 닫는다. 닫힌 이유와 시각은 함께 채운다(V1 CHECK). 시각은 원인 행동의 시각이다. */
  public void close(ClosedReason reason, Instant at) {
    requireOpen();
    status = RequestStatus.CLOSED;
    closedReason = Objects.requireNonNull(reason, "reason");
    closedAt = Objects.requireNonNull(at, "at");
  }

  /**
   * 제안을 낸 쪽이 자기 안을 바꿨다(05 {@code proposal} ②). 같은 요청이 다시 기다림이 되고 미확인 때 열린 혼자 진행 이유는 지운다.
   *
   * <p>기한을 "바꾼 시각부터 다시 센다"(03 E)는 기다림 타이머(d3)가 들어올 때 정한다. 지금은 기한을 정하는 코드가 없어 기한이 있으면 조용히 옛 기한을 남기지
   * 않고 거절한다.
   */
  public void rewait() {
    requireOpen();
    if (kind != RequestKind.PROPOSAL) {
      throw new IllegalStateException("다시 기다림은 제안 요청만이에요: " + kind);
    }
    requireNoDeadline();
    status = RequestStatus.WAITING;
    soloReason = null;
  }

  /** 기한이 이미 정해졌나(재통지·미확인 중 하나라도). */
  public boolean hasDeadline() {
    return renotifyDueAt != null || unconfirmedDueAt != null;
  }

  /** 기한 다시 세기가 필요한 전이에서 부른다. 기한이 있으면 기다림 타이머(d3) 전이라 거절한다. */
  public void requireNoDeadline() {
    if (hasDeadline()) {
      throw new IllegalStateException("기한 다시 세기는 기다림 타이머(d3)에서 정해요");
    }
  }

  private void requireOpen() {
    if (!isOpen()) {
      throw new IllegalStateException("끝난 요청은 바꾸지 않아요: " + status);
    }
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
