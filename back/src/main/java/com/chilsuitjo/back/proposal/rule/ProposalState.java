package com.chilsuitjo.back.proposal.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 돈 안건 규칙({@code ProposalTransitions.apply})이 보는 지금 상태. {@code ProposalStateStore.load}가 호실을 잠근 뒤에
 * 만든다.
 *
 * <p>행동에서 오는 값(누가·어느 제안에·언제·사유)은 {@code ActionEntry}에 있어서 여기에 두지 않는다. 지금은 동의·이견·거둠에 필요한 칸만 있고,
 * 제안·다른 안을 만들 때 회차 등 칸을 더한다.
 *
 * @param head 안건 head(상태·기다리는 안·합의한 안·기준 금액). {@code AgendaHeadStore.load}가 읽은 그대로다
 * @param waiting {@code head.currentProposalId()}가 가리키는 기다리는 안의 요약. 기다리는 안이 없으면 null
 */
public record ProposalState(AgendaState head, Waiting waiting) {

  /**
   * 기다리는 안의 요약.
   *
   * @param id 제안 ID({@code head.currentProposalId()}와 같다)
   * @param proposedBy 낸 측
   * @param total 줄 금액의 합. {@code proposal}에는 합계 칸이 없어서 {@code load}가 {@code proposal_line}을 더해 채운다
   * @param noCost 비용 없음 안인지
   */
  public record Waiting(UUID id, PartySide proposedBy, long total, boolean noCost) {}
}
