package com.chilsuitjo.back.payment.rule;

import java.util.Objects;
import java.util.UUID;

/**
 * 지불의 같이 확인한 내용이 바뀌었다는 도메인 이벤트(decisions 2026-11-16, 09 2장 "아래에서 위로 알릴 때"). 이름은 금액이지만 금액만이 아니다. 낸
 * 사람·받는 사람·날짜 등 어느 칸이 바뀌어도 낸다.
 *
 * <p>내는 때: 상대가 처음 [맞아요]를 눌러 {@code confirmedVersionId}가 정해질 때(ACK), 같이 확인한 뒤 고친 내용이 확인돼 {@code
 * Correctable.mergeConfirmed}로 확인한 판이 옮겨 갈 때. 무엇이 바뀌었는지는 가리지 않는다. 지불 기록, 보류·이견, 보충, 확인 전 고치기에서는 확인한
 * 판이 그대로라 내지 않는다.
 *
 * <p>영향은 듣는 쪽이 다시 계산해 비교한다. payment는 누가 어느 칸을 쓰는지 모른다. repair는 {@code confirmedTotal}을 읽어 {@code
 * rebase}를 부르고(합이 같으면 그대로), settlement는 그 수리 건의 후보를 다시 계산해 지금 정산 항목과 <b>다를 때만</b> 다시 확인 대기로 돌린다.
 * 날짜만 고친 확인처럼 계산이 같으면 아무것도 바꾸지 않는다.
 *
 * <p>같은 트랜잭션 안의 동기 이벤트다. 듣는 쪽은 {@code @EventListener}로 듣는다. {@code @TransactionalEventListener}나
 * 비동기 리스너에서는 {@code ActionFollowUps}·{@code ActionRequestContext}를 쓸 수 없다. payment는 지불을 저장한 뒤에 낸다.
 * 듣는 쪽이 새 값을 payment에서 다시 읽기 때문이다.
 *
 * <p>금액은 싣지 않는다. 수리 건의 기준 금액은 듣는 쪽이 {@code PaymentService.confirmedTotal}로 읽는다(decisions
 * 2026-11-24, 값의 출처를 하나로). 이 지불의 받는 사람 같은 내용이 필요하면 {@code paymentId}로 읽는다.
 *
 * <p>듣는 쪽: repair(수리비 합의 풀기 {@code ProposalService.rebase}), settlement(그 수리 건에서 온 후보를 다시 확인 대기로).
 *
 * @param roomRecordId 호실 기록
 * @param repairCaseId 지불이 딸린 수리 건
 * @param paymentId 같이 확인된 지불
 */
public record PaymentAmountConfirmed(UUID roomRecordId, UUID repairCaseId, UUID paymentId) {

  public PaymentAmountConfirmed {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(repairCaseId, "repairCaseId");
    Objects.requireNonNull(paymentId, "paymentId");
  }
}
