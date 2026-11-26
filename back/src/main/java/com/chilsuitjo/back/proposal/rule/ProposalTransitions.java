package com.chilsuitjo.back.proposal.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 돈 안건 규칙(03 1-2·E, 03 G-1 "돈 안건 장치 하나"). 수리비 부담·정산 항목·보증금이 같이 쓴다. 순수 함수다: Repository·Clock·다른 서비스를
 * 부르지 않는다(04 4-0).
 *
 * <p>지금은 동의({@code AGREE})·이견({@code DISPUTE_PROPOSAL})만 있다. 제안·다른 안·거둠은 그 기능을 만들 때 더한다.
 *
 * <p>기다림이 끝나는 행동은 늘 head의 기다리는 안({@code currentProposalId})을 비운다. {@code ProposalStateStore}가 기다리는
 * 안을 이 칸만으로 정하기 때문이다.
 */
@Component
public class ProposalTransitions implements Transitions<ProposalState> {

  /** 아무 행동도 없을 때. head는 안건 주인이 만들 때 정하므로 비어 있다. */
  @Override
  public ProposalState initial() {
    return new ProposalState(null, null);
  }

  @Override
  public ProposalState apply(ProposalState now, ActionEntry action) {
    return switch (action.type()) {
      case AGREE -> agree(now, action);
      case DISPUTE_PROPOSAL -> dispute(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 기다리는 안에 상대가 동의한다(03 E "합의 대기 → 합의됨 / 비용 없음"). 합의는 같은 안에 낸 측의 제안과 상대 측의 동의가 모두 있을 때만 생긴다(03 5장
   * 불변식 2).
   *
   * <ol>
   *   <li>동의한 안이 지금 기다리는 안이 아니면(기다리는 안이 없는 미정·이견 포함) {@code NOT_CURRENT_PROPOSAL}
   *   <li>내가 낸 안이면 {@code OWN_PROPOSAL}(06 MONEY-3c)
   *   <li>나눈 금액의 합이 기준 금액과 다르면 {@code SUM_MISMATCH}(06 MONEY-3a). 비용 없음 안과 기준 금액이 없는 안건(정산 항목·보증금)은
   *       보지 않는다
   * </ol>
   *
   * <p>합의하면 기다리는 안은 비우고 합의한 안이 이 안이 된다. 합의 뒤 바꾸자는 안에 동의하면 새 합의다(03 1-2).
   */
  private static ProposalState agree(ProposalState now, ActionEntry action) {
    ProposalState.Waiting waiting = counterpartsWaiting(now, action);
    AgendaState head = now.head();
    Long required = head.requiredTotal();
    if (!waiting.noCost() && required != null && waiting.total() != required) {
      throw new BusinessException(ErrorCode.SUM_MISMATCH);
    }
    AgendaStatus status = waiting.noCost() ? AgendaStatus.NO_COST : AgendaStatus.AGREED;
    return new ProposalState(
        new AgendaState(head.roomRecordId(), head.agenda(), status, null, waiting.id(), required),
        null);
  }

  /**
   * 기다리는 안에 상대가 이견을 남긴다(03 E "합의 대기 → 이견(사유)"). 사유는 필수이고 행동 기록의 {@code reason}에 남는다(decisions
   * 2026-11-19). 지금 안인지·상대 측인지는 {@link #agree}와 같고, 그다음 사유가 비었거나 공백뿐이면 {@code
   * REASON_REQUIRED}다({@code AnswerSupport}와 같은 기준).
   *
   * <p>기다리는 안은 비운다. 합의가 있었으면(합의 뒤 바꾸자는 안에 이견) 합의와 그 상태({@code AGREED}·{@code NO_COST})를 그대로 둔다(03
   * 1-2 "합의 뒤 새 제안"). 합의가 없었으면 {@code DISPUTE}다.
   */
  private static ProposalState dispute(ProposalState now, ActionEntry action) {
    counterpartsWaiting(now, action);
    if (action.reason() == null || action.reason().isBlank()) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    AgendaState head = now.head();
    AgendaStatus status = head.agreedProposalId() != null ? head.status() : AgendaStatus.DISPUTE;
    return new ProposalState(
        new AgendaState(
            head.roomRecordId(),
            head.agenda(),
            status,
            null,
            head.agreedProposalId(),
            head.requiredTotal()),
        null);
  }

  /**
   * 상대가 답할 수 있는 기다리는 안. 답하는 안이 지금 기다리는 안이 아니면(기다리는 안이 없는 미정·이견 포함) {@code NOT_CURRENT_PROPOSAL}, 내가
   * 낸 안이면 {@code OWN_PROPOSAL}(06 MONEY-3c).
   */
  private static ProposalState.Waiting counterpartsWaiting(ProposalState now, ActionEntry action) {
    ProposalState.Waiting waiting = now.waiting();
    if (waiting == null || !waiting.id().equals(proposalId(action))) {
      throw new BusinessException(ErrorCode.NOT_CURRENT_PROPOSAL);
    }
    if (waiting.proposedBy() == action.actorSide()) {
      throw new BusinessException(ErrorCode.OWN_PROPOSAL);
    }
    return waiting;
  }

  /** 행동의 대상 제안. 제안 행동인데 대상이 제안이 아니면 부르는 쪽의 버그다. */
  private static UUID proposalId(ActionEntry action) {
    ActionTarget target = action.target();
    if (target == null || target.kind() != TargetKind.PROPOSAL) {
      throw new IllegalArgumentException("제안 행동의 대상은 제안이에요: " + target);
    }
    return target.id();
  }
}
