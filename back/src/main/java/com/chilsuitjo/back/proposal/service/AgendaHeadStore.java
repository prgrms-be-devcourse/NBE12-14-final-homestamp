package com.chilsuitjo.back.proposal.service;

import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import java.util.UUID;

/**
 * 돈 안건의 head({@code current_*_proposal_id}·{@code agreed_*_proposal_id}·status 칸)를 가진 domain이 구현한다.
 * proposal은 이 interface로만 head를 읽고 옮긴다(05 2-4 "어느 안이 기다리는 중인지는 안건 머리가 정한다").
 *
 * <p>구현: repair {@code REPAIR_BURDEN}({@code repair_case}), settlement {@code
 * SETTLEMENT_ITEM}({@code settlement_item})·{@code DEPOSIT}({@code settlement}). 구현은 각 domain의
 * {@code repository/}에 둔다. Spring이 구현들을 {@code List<AgendaHeadStore>}로 넣어 주고 proposal은 {@link
 * #kind}로 고른다({@code Correctable}과 같은 방식). 그래서 코드 의존은 head domain → proposal 한 방향이다(09 3장 방향 표의
 * {@code repair → proposal}·{@code settlement → proposal}).
 *
 * <p>구현은 rule을 검사하지 않는다(rule은 {@code ProposalTransitions}에만). {@link AgendaState#status()}와 domain의
 * status enum({@code MoneyStatus}·{@code SettlementItemStatus}·{@code DepositStatus}) 사이 변환은 구현이
 * 맡는다.
 */
public interface AgendaHeadStore {

  /** 이 구현이 맡는 안건 종류. 종류마다 구현은 하나다. */
  AgendaKind kind();

  /**
   * 지금 head. 호실을 잠근 뒤에 불리므로 읽은 값은 최신이다(09 3-1 "계약"). 다른 호실의 대상이면 {@code NOT_FOUND}.
   *
   * @param roomRecordId 안건이 속한 호실 기록 ID
   * @param targetId 안건 대상 ID(수리 건·정산 항목·정산)
   */
  AgendaState load(UUID roomRecordId, UUID targetId);

  /**
   * {@code ProposalTransitions}가 정한 다음 head를 쓴다. 대상은 {@link AgendaState#agenda()}로 정한다.
   *
   * @param next 쓸 head 값
   */
  void save(AgendaState next);
}
