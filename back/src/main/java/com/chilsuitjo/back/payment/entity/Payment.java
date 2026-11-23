package com.chilsuitjo.back.payment.entity;

import com.chilsuitjo.back.payment.rule.ConfirmStrength;
import com.chilsuitjo.back.payment.rule.PaymentResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지불 머리({@code state.payment}, 05 2-5, C 테이블). 금액·낸 사람·받는 사람·날짜는 지불 판에 두고, 이 행은 지금 판과 같이 확인한 판을
 * 가리킨다.
 *
 * <p>확인 강도와 상대 응답은 따로 둔다. 수리비 나누기의 기준 금액에는 {@code response = ACK}인 지불의 확인된 판 금액만 들어간다(05 2-5).
 *
 * <p>상태 변경 메서드는 지불 기능(MONEY-1~2)을 구현할 때 상태 전이·StateStore와 함께 더한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "state", name = "payment")
public class Payment {

  /** 첫 판과 서로를 가리키므로 앱이 저장 전에 만든다({@code Uuids.v7()}, 05 0장 {@code id}). */
  @Id private UUID id;

  @Column(name = "room_record_id", nullable = false, updatable = false)
  private UUID roomRecordId;

  @Column(name = "repair_case_id", nullable = false, updatable = false)
  private UUID repairCaseId;

  /** 지금 판. 지연 FK라 첫 판과 같은 트랜잭션에서 저장한다. */
  @Column(name = "current_version_id", nullable = false)
  private UUID currentVersionId;

  /** 같이 확인한 판. 상대가 확인하기 전에는 null. */
  @Column(name = "confirmed_version_id")
  private UUID confirmedVersionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "confirm_strength", nullable = false, length = 10)
  private ConfirmStrength confirmStrength;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private PaymentResponse response;

  /** 상대에게 처음 보인 시각. 실제로 보이게 될 때 채운다(05 2-10). */
  @Column(name = "first_shown_at")
  private Instant firstShownAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version private Long version;

  /**
   * 지불 등록. 증빙 카드가 있으면 증빙 있음, 없으면 한쪽 기록으로 시작한다(05 2-5). 상대 응답은 아직 없다.
   *
   * <p>{@code id}와 {@code currentVersionId}는 {@code Uuids.v7()}로 미리 만든다. {@code @Version}이 비어 있어
   * 저장할 때 새 행으로 들어간다.
   */
  public Payment(
      UUID id,
      UUID roomRecordId,
      UUID repairCaseId,
      UUID currentVersionId,
      boolean hasEvidence,
      Instant createdAt) {
    this.id = id;
    this.roomRecordId = roomRecordId;
    this.repairCaseId = repairCaseId;
    this.currentVersionId = currentVersionId;
    this.confirmStrength = hasEvidence ? ConfirmStrength.EVIDENCE : ConfirmStrength.ONE_SIDE;
    this.response = PaymentResponse.NONE;
    this.createdAt = createdAt;
  }
}
