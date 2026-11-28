package com.chilsuitjo.back.payment.repository;

import com.chilsuitjo.back.payment.entity.Payment;
import com.chilsuitjo.back.payment.rule.PayeeKind;
import com.chilsuitjo.back.payment.rule.PayerKind;
import com.chilsuitjo.back.payment.rule.SimilarPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 지불 머리({@code state.payment}, C). 모든 조회는 호실 기록 ID를 함께 걸어 다른 호실의 수리 건이면 비게 한다. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByIdAndRoomRecordId(UUID id, UUID roomRecordId);

  /** 링크 화면의 돈 기록 있음(06 LINK-1d). 확인 전 지불도 센다. */
  boolean existsByRoomRecordIdAndRepairCaseId(UUID roomRecordId, UUID repairCaseId);

  /** 이 수리 건의 지불 ID(기록 순서). 사진 한도를 셀 때 쓴다. */
  @Query(
      """
      SELECT p.id FROM Payment p
      WHERE p.roomRecordId = :roomRecordId AND p.repairCaseId = :repairCaseId
      ORDER BY p.createdAt, p.id
      """)
  List<UUID> findIds(
      @Param("roomRecordId") UUID roomRecordId, @Param("repairCaseId") UUID repairCaseId);

  /**
   * 수리비 나누기 기준 금액(05 2-5, decisions 2026-11-24): {@code response = ACK}인 지불의 같이 확인한 판 금액 합. 그 판의 받는
   * 사람이 상대 당사자면 뺀다. 없으면 0.
   */
  @Query(
      """
      SELECT COALESCE(SUM(v.amount), 0L)
      FROM Payment p JOIN PaymentVersion v ON v.id = p.confirmedVersionId
      WHERE p.roomRecordId = :roomRecordId
        AND p.repairCaseId = :repairCaseId
        AND p.response = com.chilsuitjo.back.payment.rule.PaymentResponse.ACK
        AND v.payeeKind <> com.chilsuitjo.back.payment.rule.PayeeKind.COUNTERPART
      """)
  long sumConfirmed(
      @Param("roomRecordId") UUID roomRecordId, @Param("repairCaseId") UUID repairCaseId);

  /**
   * 이 수리 건에서 지금 판의 방향·금액이 같은 지불(06 MONEY-1a·1c). 기간은 여기서 거르지 않는다(규칙은 {@code PaymentTransitions}).
   * 기록 순서.
   */
  @Query(
      """
      SELECT new com.chilsuitjo.back.payment.rule.SimilarPayment(
          p.id, v.recordedBySide, p.response, v.paidOn, p.createdAt)
      FROM Payment p JOIN PaymentVersion v ON v.id = p.currentVersionId
      WHERE p.roomRecordId = :roomRecordId
        AND p.repairCaseId = :repairCaseId
        AND v.payerKind = :payerKind
        AND v.payeeKind = :payeeKind
        AND v.amount = :amount
      ORDER BY p.createdAt, p.id
      """)
  List<SimilarPayment> findSameDirectionAndAmount(
      @Param("roomRecordId") UUID roomRecordId,
      @Param("repairCaseId") UUID repairCaseId,
      @Param("payerKind") PayerKind payerKind,
      @Param("payeeKind") PayeeKind payeeKind,
      @Param("amount") long amount);
}
