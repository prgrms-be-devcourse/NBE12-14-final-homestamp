package com.chilsuitjo.back.proposal.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.entity.Proposal;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import com.chilsuitjo.back.proposal.rule.ProposalState;
import com.chilsuitjo.back.proposal.service.AgendaHeadStore;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 돈 안건 상태 읽기·쓰기({@code StateStore<ProposalState>}, 09 3-1). 규칙은 검사하지 않는다(규칙은 {@code
 * ProposalTransitions}에만). head는 안건 주인의 {@link AgendaHeadStore}로만 읽고 쓴다(decisions 2026-11-24).
 *
 * <p><b>기다리는 안</b>은 head의 {@code currentProposalId}만으로 정한다. 그래서 기다림이 끝나는 행동(동의·이견·거둠, 금액이 바뀌어 풀림)은
 * 늘 그 칸을 비운다. 05 2-4의 "응답 요청이 WAITING·UNCONFIRMED"는 이 약속으로 함께 지켜진다.
 *
 * <p>지금은 동의({@code AGREE})·이견({@code DISPUTE_PROPOSAL})만 쓴다. 제안·다른 안처럼 금액 줄을 들고 가야 하는 행동은 그 행동만 만드는
 * 메서드로 뺀다.
 */
@Component
public class ProposalStateStore implements StateStore<ProposalState> {

  private final ProposalRepository proposals;
  private final ProposalLineRepository lines;
  private final Map<AgendaKind, AgendaHeadStore> heads;
  private final WaitService waits;

  public ProposalStateStore(
      ProposalRepository proposals,
      ProposalLineRepository lines,
      List<AgendaHeadStore> heads,
      WaitService waits) {
    this.proposals = proposals;
    this.lines = lines;
    this.heads = byKind(heads);
    this.waits = waits;
  }

  /**
   * 행동의 대상 제안이 붙은 안건의 지금 상태. {@code waiting}은 대상 제안이 아니라 <b>head가 가리키는 안</b>이다(옛 화면에서 지난 안을 누르면
   * {@code apply}가 두 ID를 비교해 {@code NOT_CURRENT_PROPOSAL}로 거절한다).
   *
   * @throws BusinessException 대상 제안이 없거나 다른 호실이면 {@code NOT_FOUND}
   */
  @Override
  public ProposalState load(ActionCommand command) {
    UUID roomRecordId = command.roomRecordId();
    Proposal target =
        proposals
            .findByIdAndRoomRecordId(command.target().id(), roomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    AgendaRef agenda = target.agenda();
    AgendaState head = headOf(agenda.kind()).load(roomRecordId, agenda.targetId());
    return new ProposalState(head, waiting(head));
  }

  /** {@code apply}가 정한 head를 쓰고, 기다림이 끝났으면 그 안건의 응답 요청을 닫는다. */
  @Override
  public SavedState save(ActionCommand command, ProposalState next) {
    AgendaState head = next.head();
    switch (command.type()) {
      // 동의·이견은 둘 다 상대의 답이라 그 안건의 응답 요청을 "답함"으로 닫는다
      case AGREE, DISPUTE_PROPOSAL -> {
        headOf(head.agenda().kind()).save(head);
        waits.answered(requestTarget(head.agenda()), RequestKind.PROPOSAL, command.actor().side());
      }
      default -> throw new IllegalStateException("아직 저장할 수 없는 행동이에요: " + command.type());
    }
    return SavedState.unchanged();
  }

  /** head가 가리키는 기다리는 안의 요약. 가리키는 안이 없으면 null. */
  private ProposalState.Waiting waiting(AgendaState head) {
    UUID currentId = head.currentProposalId();
    if (currentId == null) {
      return null;
    }
    // head와 제안은 V1 복합 FK로 같은 호실·같은 안건이라 없을 수 없다
    Proposal current =
        proposals
            .findByIdAndRoomRecordId(currentId, head.roomRecordId())
            .orElseThrow(() -> new IllegalStateException("head가 없는 제안을 가리켜요: " + currentId));
    return new ProposalState.Waiting(
        current.getId(),
        current.getProposedBySide(),
        lines.sumAmount(currentId),
        current.isNoCost());
  }

  private AgendaHeadStore headOf(AgendaKind kind) {
    AgendaHeadStore store = heads.get(kind);
    if (store == null) {
      throw new IllegalStateException("이 안건의 head 저장소가 아직 없어요: " + kind);
    }
    return store;
  }

  /** 제안 응답 요청은 제안이 아니라 안건을 가리킨다(05 {@code response_request} {@code PROPOSAL}). */
  private static RequestTarget requestTarget(AgendaRef agenda) {
    TargetKind kind =
        switch (agenda.kind()) {
          case REPAIR_BURDEN -> TargetKind.REPAIR_CASE;
          case SETTLEMENT_ITEM -> TargetKind.SETTLEMENT_ITEM;
          case DEPOSIT -> TargetKind.SETTLEMENT;
        };
    return RequestTarget.of(kind, agenda.targetId());
  }

  /** 종류마다 구현은 하나다({@link AgendaHeadStore#kind}). 둘이면 어느 쪽에 쓸지 모르므로 뜰 때 막는다. */
  private static Map<AgendaKind, AgendaHeadStore> byKind(List<AgendaHeadStore> stores) {
    Map<AgendaKind, AgendaHeadStore> map = new EnumMap<>(AgendaKind.class);
    for (AgendaHeadStore store : stores) {
      if (map.putIfAbsent(store.kind(), store) != null) {
        throw new IllegalStateException("같은 안건 종류의 head 저장소가 둘이에요: " + store.kind());
      }
    }
    return map;
  }
}
