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
   *     <p>기다리는 안이 없을 때만 받는다(decisions 2026-11-26): 미정·이견이면 합의 대기, 합의됨·비용 없음이면 합의는 그대로이고 새 안은 바꾸자는
   *     안이다(MONEY-3d). 기다리는 안이 있으면 내 안 바꾸기·역제안이라 아직 받지 않는다. 지금은 수리비 부담({@code REPAIR_BURDEN})만 받는다.
   * @param cmd 금액 줄·비용 없음·사유
   * @return 만든 제안 ID와 재요청 여부. {@link ProposalResult#agenda()}는 {@code null}이다(만드는 행동의 응답은 만든 ID만,
   *     06 4-0)
   * @throws com.chilsuitjo.back.global.error.BusinessException 줄 모양이 안건에 맞지 않으면 {@code
   *     VALIDATION_FAILED}, 제3자 설명이 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 기다리는 안이 있으면 {@code
   *     INVALID_STATE}, 줄 금액의 합이 전체 금액과 다르면 {@code SUM_MISMATCH}, 다른 호실의 대상이면 {@code NOT_FOUND}, 호실
   *     단위 거절은 pipeline의 오류({@code SETTLED} 등)
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

  /**
   * 기다리는 안에 상대 측이 동의한다({@code AGREE}, 06 MONEY-3). {@code POST /api/proposals/{id}/agree}를 {@code
   * ProposalController}가 받아 부른다. 트랜잭션·측·재요청은 {@link #propose}와 같다.
   *
   * <p><b>아직 계약과 다르다</b>: 돌려주는 {@link ProposalResult#agenda()}는 {@code null}이다. 처리 뒤 안건(합의·이력)은 화면이
   * 수리 건 상세를 다시 불러와 본다. 안건 조립은 상세 조회의 돈 칸을 만들 때 채운다.
   *
   * @param proposalId 동의할 제안 ID
   * @param memberId 동의하는 회원 ID
   * @param idempotencyKey 요청의 {@code Idempotency-Key}
   * @return 동의한 제안 ID와 재요청 여부
   * @throws com.chilsuitjo.back.global.error.BusinessException 지금 기다리는 안이 아니면 {@code
   *     NOT_CURRENT_PROPOSAL}, 내 제안이면 {@code OWN_PROPOSAL}, 나눈 금액의 합이 전체 금액과 다르면 {@code
   *     SUM_MISMATCH}, 없는 제안이면 {@code NOT_FOUND}, 호실 단위 거절은 pipeline의 오류
   */
  ProposalResult agree(UUID proposalId, UUID memberId, String idempotencyKey);

  /**
   * 기다리는 안에 상대 측이 이견을 남긴다({@code DISPUTE_PROPOSAL}, 03 E). {@code POST
   * /api/proposals/{id}/dispute}를 {@code ProposalController}가 받아 부른다. 사유는 필수이고 행동 기록의 {@code
   * reason}에 남는다(decisions 2026-11-19). 합의 뒤 바꾸자는 안에 남긴 이견이면 합의는 그대로다(03 1-2). 트랜잭션·측·재요청·돌려주는 값은
   * {@link #agree}와 같다.
   *
   * @param proposalId 이견을 남길 제안 ID
   * @param memberId 이견을 남기는 회원 ID
   * @param idempotencyKey 요청의 {@code Idempotency-Key}
   * @param reason 이견 사유
   * @return 그 제안 ID와 재요청 여부
   * @throws com.chilsuitjo.back.global.error.BusinessException 사유가 없거나 공백뿐이면 {@code
   *     REASON_REQUIRED}, 주민등록번호 형태면 {@code RESIDENT_ID_BLOCKED}, 지금 기다리는 안이 아니면 {@code
   *     NOT_CURRENT_PROPOSAL}, 내 제안이면 {@code OWN_PROPOSAL}, 없는 제안이면 {@code NOT_FOUND}, 호실 단위 거절은
   *     pipeline의 오류
   */
  ProposalResult dispute(UUID proposalId, UUID memberId, String idempotencyKey, String reason);
}
