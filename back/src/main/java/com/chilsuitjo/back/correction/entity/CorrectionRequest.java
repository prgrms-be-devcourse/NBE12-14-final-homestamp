package com.chilsuitjo.back.correction.entity;

import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 고친 내용({@code state.correction_request}, 05 2-5, C 테이블. 만든 뒤에는 상태만 바뀐다). 같이 확인한 뒤 칸 하나를 고칠 때 생긴다:
 * 칸 + 새 값 + 기준 판. 상대가 확인하면 그때의 도장 판 + 이 칸만 합쳐 새 판을 만든다(03 G).
 *
 * <p>대상 칸(수리 건·지불·점검 항목·송금)은 최대 하나. 모두 비면 호실 기록의 입주일. {@code targetKey}는 대상 종류 + ID를 합친 글자로 칸마다
 * WAITING 하나 제약({@code ux_correction_waiting})에 쓴다.
 *
 * <p>닫힌 이유({@code closedReason})·시각은 {@code CLOSED}일 때만 함께 채운다(V1 CHECK). 값은 {@link ClosedReason} 중
 * {@code SHARING_STOPPED}·{@code COUNTERPART_WITHDRAWN}·{@code SETTLED}·{@code CLOSED_BY_BOTH}만.
 */
@Entity
@Table(schema = "state", name = "correction_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorrectionRequest {

  @Id
  @GeneratedValue
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  /** 고칠 칸 이름. */
  @Column(nullable = false, length = 40, updatable = false)
  private String field;

  /** 새 값(JSON 글자). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "new_value", nullable = false, updatable = false)
  private String newValue;

  /** 그때 도장 찍힌 판. 판 테이블이 대상마다 달라 FK 없음. */
  @Column(name = "base_version_id", updatable = false)
  private UUID baseVersionId;

  @Column(name = "repair_case_id", updatable = false)
  private UUID repairCaseId;

  @Column(name = "payment_id", updatable = false)
  private UUID paymentId;

  @Column(name = "inspection_item_id", updatable = false)
  private UUID inspectionItemId;

  @Column(name = "settlement_transfer_id", updatable = false)
  private UUID settlementTransferId;

  @Column(name = "target_key", nullable = false, length = 80, updatable = false)
  private String targetKey;

  /** 거절된 고친 내용을 근거를 더해 다시 보낼 때 이전 행. */
  @Column(name = "retry_of_id", updatable = false)
  private UUID retryOfId;

  @Enumerated(EnumType.STRING)
  @Column(name = "requested_by_side", nullable = false, length = 10, updatable = false)
  private PartySide requestedBySide;

  @Column(updatable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private CorrectionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "closed_reason", length = 30)
  private ClosedReason closedReason;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /** 대상 칸은 최대 하나만 넘긴다(나머지 null). 처음 상태는 WAITING. */
  public CorrectionRequest(
      UUID roomRecordId,
      String field,
      String newValue,
      UUID baseVersionId,
      UUID repairCaseId,
      UUID paymentId,
      UUID inspectionItemId,
      UUID settlementTransferId,
      String targetKey,
      UUID retryOfId,
      PartySide requestedBySide,
      String reason,
      Instant createdAt) {
    this.roomRecordId = roomRecordId;
    this.field = field;
    this.newValue = newValue;
    this.baseVersionId = baseVersionId;
    this.repairCaseId = repairCaseId;
    this.paymentId = paymentId;
    this.inspectionItemId = inspectionItemId;
    this.settlementTransferId = settlementTransferId;
    this.targetKey = targetKey;
    this.retryOfId = retryOfId;
    this.requestedBySide = requestedBySide;
    this.reason = reason;
    this.createdAt = createdAt;
    this.status = CorrectionStatus.WAITING;
  }

  /** 대상 기록의 종류. 대상 칸이 모두 비면 호실 기록의 입주일이다. */
  public CorrectionTargetKind targetKind() {
    if (repairCaseId != null) {
      return CorrectionTargetKind.REPAIR_CASE;
    }
    if (paymentId != null) {
      return CorrectionTargetKind.PAYMENT;
    }
    if (inspectionItemId != null) {
      return CorrectionTargetKind.INSPECTION_ITEM;
    }
    if (settlementTransferId != null) {
      return CorrectionTargetKind.SETTLEMENT_TRANSFER;
    }
    return CorrectionTargetKind.MOVE_IN_DATE;
  }

  /** 대상 기록의 ID. 입주일이면 호실 기록 ID. */
  public UUID targetId() {
    return switch (targetKind()) {
      case REPAIR_CASE -> repairCaseId;
      case PAYMENT -> paymentId;
      case INSPECTION_ITEM -> inspectionItemId;
      case SETTLEMENT_TRANSFER -> settlementTransferId;
      case MOVE_IN_DATE -> roomRecordId;
    };
  }

  /**
   * 상대가 [맞아요]로 확인했다. 다음 상태는 {@code CorrectionTransitions}가 정한 값이다. 동시에 바뀌면 {@code @Version}이
   * 막는다(409 {@code VERSION_CONFLICT}). 아래 메서드들도 같다.
   */
  public void accept() {
    this.status = CorrectionStatus.ACCEPTED;
  }

  /** 상대가 보류·이견으로 답했다. 구분과 사유는 응답 행동에 남는다(decisions 2026-11-16). */
  public void reject() {
    this.status = CorrectionStatus.REJECTED;
  }

  /** 고친 측이 답을 받기 전에 거뒀다. */
  public void withdraw() {
    this.status = CorrectionStatus.WITHDRAWN;
  }

  /** 같은 칸을 다시 고쳐 새 고친 내용이 이것을 대신한다(05 2-5 "다시 고치면 이전 것은 SUPERSEDED"). */
  public void supersede() {
    this.status = CorrectionStatus.SUPERSEDED;
  }
}
