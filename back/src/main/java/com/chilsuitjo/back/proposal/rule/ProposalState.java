package com.chilsuitjo.back.proposal.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * 돈 안건 규칙({@code ProposalTransitions.apply})이 보는 지금 상태. {@code ProposalStateStore.load}가 호실을 잠근 뒤에
 * 만든다.
 *
 * <p>행동에서 오는 값(누가·어느 제안에·언제·사유)은 {@code ActionEntry}에 있어서 여기에 두지 않는다. 다만 제안({@code PROPOSE})은 아직
 * 저장되지 않은 안이라 {@code load}가 DB에서 읽을 수 없어서, 규칙이 볼 요약({@link Draft})을 요청에서 받아 여기에 둔다. 금액 줄은 행동
 * payload에 넣지 않는다(05 2-3 "작은 부가 값만", 원본은 {@code proposal_line} 하나).
 *
 * @param head 안건 head(상태·기다리는 안·합의한 안·기준 금액). {@code AgendaHeadStore.load}가 읽은 그대로다
 * @param waiting {@code head.currentProposalId()}가 가리키는 기다리는 안의 요약. 기다리는 안이 없으면 null
 * @param draft 지금 내는 제안의 요약. 제안이 아닌 행동(동의·이견)과 같은 키로 다시 온 제안이면 null
 */
public record ProposalState(AgendaState head, Waiting waiting, Draft draft) {

  /** 제안을 내지 않는 행동(동의·이견)의 상태. */
  public ProposalState(AgendaState head, Waiting waiting) {
    this(head, waiting, null);
  }

  /**
   * 기다리는 안의 요약.
   *
   * @param id 제안 ID({@code head.currentProposalId()}와 같다)
   * @param proposedBy 낸 측
   * @param total 줄 금액의 합. {@code proposal}에는 합계 칸이 없어서 {@code load}가 {@code proposal_line}을 더해 채운다
   * @param noCost 비용 없음 안인지
   */
  public record Waiting(UUID id, PartySide proposedBy, long total, boolean noCost) {}

  /**
   * 지금 내는 제안의 요약. 줄 전체는 저장하는 쪽({@code ProposalStateStore.proposing})이 들고 있고, 규칙은 합계 검사에 필요한 값만 본다.
   *
   * @param total 줄 금액의 합. 비용 없음 안이면 0
   * @param noCost 비용 없음 안인지
   */
  public record Draft(long total, boolean noCost) {}
}
