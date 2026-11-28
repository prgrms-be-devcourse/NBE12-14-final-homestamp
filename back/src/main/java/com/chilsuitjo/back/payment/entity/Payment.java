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
import java.util.Objects;
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
 * <p>상태는 {@code PaymentTransitions}가 정한 값 그대로 바꾼다. 규칙은 여기서 보지 않는다. 동시에 바뀌면 {@code @Version}이
 * 막는다(409 {@code VERSION_CONFLICT}).
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
    this.response = PaymentResponse.PENDING;
    this.createdAt = createdAt;
  }

  /**
   * 상대의 보류·이견을 남긴다(06 MONEY-2a). 지금 판·같이 확인한 판·확인 강도는 그대로다(원래 기록은 그대로이고 이견은 행동 기록에 나란히 남는다). 그래서 기준
   * 금액에 들어가지 않는다. [맞아요]는 {@link #confirmCurrent}로 남긴다.
   */
  public void changeResponse(PaymentResponse next) {
    Objects.requireNonNull(next, "next");
    if (next == PaymentResponse.ACK) {
      throw new IllegalArgumentException("맞아요는 confirmCurrent로 남겨요");
    }
    this.response = next;
  }

  /**
   * 상대가 지금 판에 [맞아요]를 눌렀다(03 F "금액 확인", 05 2-5). 지금 판이 같이 확인한 판이 되고 확인 강도는 양측 확인이다. 응답은 지금 판에만 할 수
   * 있어서(본 판 번호가 같아야 함, {@code AnswerSupport.answer}) 지금 판을 가리키면 된다. 부르는 쪽은 저장한 뒤 {@code
   * PaymentAmountConfirmed}를 낸다.
   */
  public void confirmCurrent() {
    this.confirmedVersionId = currentVersionId;
    this.confirmStrength = ConfirmStrength.BOTH;
    this.response = PaymentResponse.ACK;
  }
}
