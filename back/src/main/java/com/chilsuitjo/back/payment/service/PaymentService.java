package com.chilsuitjo.back.payment.service;

import com.chilsuitjo.back.payment.dto.PaymentCreateRequest;
import com.chilsuitjo.back.payment.dto.PaymentRecordResult;
import com.chilsuitjo.back.room.dto.RoomAccess;
import java.util.UUID;

/** 다른 도메인이 지불을 부르는 창구(#111). {@link JpaPaymentService}가 구현한다. */
public interface PaymentService {

  /**
   * 지불 기록(MONEY-1, 06 {@code POST /api/repair-cases/{id}/payments}). {@code repairCaseId}가 이 호실의 수리
   * 건인지는 부르는 쪽({@code RepairController})이 먼저 확인한다(decisions 2026-11-16). 여기서는 다시 확인하지 않는다.
   *
   * <p>같은 (회원, 키)로 다시 오면 새로 만들지 않고 처음 만든 지불 ID를 {@code replayed = true}로 돌려준다. 같은 호실이면 수리 건이 달라도 처음
   * 결과다(새 기록을 만드는 행동은 종류만 비교, 09 4장 순서 2).
   *
   * @param access 이 회원이 이 호실에서 무엇인지
   * @param idempotencyKey 화면이 보낸 {@code Idempotency-Key}
   * @param repairCaseId 지불이 딸린 수리 건
   * @param req 낸 사람·받는 사람·금액·날짜·증빙
   * @return 지불 ID와 다시 온 요청인지
   * @throws com.chilsuitjo.back.global.error.BusinessException {@code VALIDATION_FAILED}(키가 비었거나
   *     {@code #}가 들었거나 64자 초과), {@code IDEMPOTENCY_KEY_REUSED}(같은 키를 다른 호실에), {@code
   *     DUPLICATE_PAYMENT}(같은 지불이 이미 있음, MONEY-1a·1c)
   */
  PaymentRecordResult record(
      RoomAccess access, String idempotencyKey, UUID repairCaseId, PaymentCreateRequest req);

  /**
   * 가입 전 링크 화면에 보여 줄 지불 기록 존재 여부(06 LINK-1d, 4-4). 상대가 확인하지 않은 지불도 포함하고 금액·낸 사람·날짜는 주지 않는다. 링크의
   * 유효성과 열람 범위는 부르는 쪽에서 먼저 확인한다.
   *
   * @param roomRecordId 링크가 가리키는 호실 기록
   * @param repairCaseId 링크가 가리키는 수리 건
   * @return 이 호실의 이 수리 건에 지불이 하나라도 있으면 true. 없거나 다른 호실의 수리 건이면 false
   */
  boolean hasMoney(UUID roomRecordId, UUID repairCaseId);

  /**
   * 수리 건의 수리비 나누기 기준 금액(03 1-2 "금액", 05 2-5). 상대가 [맞아요]를 누른({@code response = ACK}) 지불의 같이 확인한 판 금액
   * 합이다. 받는 사람이 상대 당사자({@code PayeeKind.COUNTERPART})인 지불은 당사자끼리 주고받은 돈이라 빼고, 정산 후보 계산의 순 지출에서 따로
   * 센다.
   *
   * <p>부르는 곳: repair의 {@code AgendaHeadStore}가 {@code requiredTotal}을 채울 때, {@code
   * PaymentAmountConfirmed} 리스너가 {@code rebase}에 넘길 값을 읽을 때(09 3장 {@code repair → payment},
   * decisions 2026-11-24).
   *
   * @param roomRecordId 호실 기록
   * @param repairCaseId 수리 건
   * @return 기준 금액. 확인된 지불이 없거나 다른 호실의 수리 건이면 0
   */
  long confirmedTotal(UUID roomRecordId, UUID repairCaseId);
}
