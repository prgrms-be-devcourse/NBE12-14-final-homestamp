package com.chilsuitjo.back.proposal.service;

import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.dto.AgendaResult;
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import java.util.UUID;

/**
 * 돈 안건 장치의 입구(03 G-1 "백엔드: 돈 안건"). 제안·동의·이견·다른 안·거둠을 수리비 부담·정산 항목·보증금이 같이 쓴다. 안건마다 다른 것은 head가 어디에
 * 있나뿐이고, head는 {@link AgendaHeadStore}로만 읽고 옮긴다(decisions 2026-11-24).
 *
 * <p>부르는 쪽: repair({@code repair → proposal}), settlement({@code settlement → proposal}). 09 3장 방향
 * 표.
 */
public interface ProposalService {

  /**
   * 제안을 낸다({@code PROPOSE}, 06 MONEY-3). {@code POST /api/repair-cases/{id}/proposals}는 {@code
   * RepairController}가 받아 이 호실의 수리 건인지 확인한 뒤 부른다(decisions 2026-11-17).
   *
   * <p>{@code ActionPipeline}을 지나므로 부르는 쪽은 {@code @Transactional}을 두지 않는다. 회원의 측은 pipeline이 잠금 뒤에
   * 정하므로 넘기지 않는다(09 3-1 "계약"). 같은 {@code idempotencyKey}로 다시 오면 처음 결과를 {@link
   * ProposalResult#replayed()} {@code true}로 돌려준다(06 4-0).
   *
   * @param roomRecordId 안건이 속한 호실 기록 ID
   * @param memberId 제안하는 회원 ID
   * @param idempotencyKey 요청의 {@code Idempotency-Key}
   * @param agenda 제안할 안건
   * @param cmd 금액 줄·비용 없음·사유
   * @return 만든 제안 ID와 처리 뒤 안건
   * @throws com.chilsuitjo.back.global.error.BusinessException 줄 금액의 합이 전체 금액과 다르면 {@code
   *     SUM_MISMATCH}, 다른 호실의 대상이면 {@code NOT_FOUND}, 호실 단위 거절은 pipeline의 오류({@code SETTLED} 등)
   */
  ProposalResult propose(
      UUID roomRecordId,
      UUID memberId,
      String idempotencyKey,
      AgendaRef agenda,
      ProposalCommand cmd);

  /**
   * 바탕 금액이 같이 확인돼 바뀌었을 때 합의를 푼다(03 E "지불 합계 변경", 06 MONEY-3b·3e). 행동으로 남기지 않고 원인 행동({@code
   * RESPOND_AMOUNT} 등)에 딸린 일이다(decisions 2026-11-17). 그래서 멱등 키를 받지 않고, 원인 행동의 트랜잭션 안에서만 부른다.
   *
   * <p>부르는 곳: repair가 {@code PaymentAmountConfirmed}를 듣고 부른다(decisions 2026-11-16). 합의한 금액의 합이 새 전체
   * 금액과 다르면 head의 현재·합의 제안을 비우고 기다리던 안의 응답 요청을 {@code CLOSED}({@code AMOUNT_CHANGED})로 닫는다(05 2-4
   * ④). 이전 합의는 참고로 남는다.
   *
   * @param roomRecordId 안건이 속한 호실 기록 ID
   * @param agenda 바탕 금액이 바뀐 안건
   * @param newRequiredTotal 새로 같이 확인된 지불의 합
   * @return 처리 뒤 안건
   */
  AgendaResult rebase(UUID roomRecordId, AgendaRef agenda, long newRequiredTotal);
}
