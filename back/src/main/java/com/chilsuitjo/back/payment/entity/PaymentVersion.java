package com.chilsuitjo.back.payment.entity;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * 지불 판({@code record.payment_version}, 05 2-5, B 테이블). 고치면 이전 판을 가리키는 새 판을 만든다. {@code @Immutable}과
 * DB 트리거로 기존 판의 변경을 막는다.
 *
 * <p>ID는 머리({@link Payment})와 함께 저장 전에 {@code Uuids.v7()}로 만든다(05 0장 {@code id}). {@link
 * Persistable}로 새 행을 알려 Spring Data가 persist로 저장하게 한다.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    schema = "record",
    name = "payment_version",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_payment_version_no",
            columnNames = {"payment_id", "no"}))
public class PaymentVersion implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  /** 같은 지불 안의 판 번호(1부터). */
  @Column(nullable = false, updatable = false)
  private int no;

  @Enumerated(EnumType.STRING)
  @Column(name = "payer_kind", nullable = false, length = 20, updatable = false)
  private PayerKind payerKind;

  /** 제3자면 실명·연락처 대신 관계 설명(예: 가족, 윗집, 보험사). */
  @Column(name = "payer_label", length = 30, updatable = false)
  private String payerLabel;

  @Enumerated(EnumType.STRING)
  @Column(name = "payee_kind", nullable = false, length = 20, updatable = false)
  private PayeeKind payeeKind;

  @Column(name = "payee_label", length = 30, updatable = false)
  private String payeeLabel;

  /** 원 단위 금액. 0 이상(V1 CHECK). */
  @Column(nullable = false, updatable = false)
  private long amount;

  /** 사용자가 입력한 지불일. 서버 기록 시각과 구별한다. */
  @Column(name = "paid_on", nullable = false, updatable = false)
  private LocalDate paidOn;

  @Enumerated(EnumType.STRING)
  @Column(name = "recorded_by_side", nullable = false, length = 10, updatable = false)
  private PartySide recordedBySide;

  /** 증빙 종류. 증빙 존재 여부는 붙은 사진 카드로 판단한다(05 2-5). */
  @Column(name = "evidence_kind", length = 30, updatable = false)
  private String evidenceKind;

  /** 이전 판. 첫 판이면 null. */
  @Column(name = "previous_id", updatable = false)
  private UUID previousId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Transient
  @Getter(AccessLevel.NONE)
  private boolean newRow = true;

  public PaymentVersion(
      UUID id,
      UUID roomRecordId,
      UUID paymentId,
      int no,
      PayerKind payerKind,
      String payerLabel,
      PayeeKind payeeKind,
      String payeeLabel,
      long amount,
      LocalDate paidOn,
      PartySide recordedBySide,
      String evidenceKind,
      UUID previousId,
      Instant createdAt) {
    if (no < 1) {
      throw new IllegalArgumentException("판 번호는 1 이상이에요");
    }
    if (amount < 0) {
      throw new IllegalArgumentException("금액은 0 이상이에요");
    }
    this.id = id;
    this.roomRecordId = roomRecordId;
    this.paymentId = paymentId;
    this.no = no;
    this.payerKind = payerKind;
    this.payerLabel = payerLabel;
    this.payeeKind = payeeKind;
    this.payeeLabel = payeeLabel;
    this.amount = amount;
    this.paidOn = paidOn;
    this.recordedBySide = recordedBySide;
    this.evidenceKind = evidenceKind;
    this.previousId = previousId;
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
