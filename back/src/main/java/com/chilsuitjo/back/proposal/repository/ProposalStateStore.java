package com.chilsuitjo.back.proposal.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.dto.MoneyLine;
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.entity.Proposal;
import com.chilsuitjo.back.proposal.entity.ProposalLine;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaState;
import com.chilsuitjo.back.proposal.rule.ProposalState;
import com.chilsuitjo.back.proposal.service.AgendaHeadStore;
import java.time.Instant;
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
 * <p>이 빈은 동의({@code AGREE})·이견({@code DISPUTE_PROPOSAL})에 쓴다. 제안({@code PROPOSE})처럼 금액 줄을 들고 가야 하는
 * 행동은 그 요청만 쓰는 저장 방법({@link #proposing})을 만든다({@code RepairStateStore.registering}과 같은 모양).
 */
@Component
public class ProposalStateStore implements StateStore<ProposalState> {

  private final ProposalRepository proposals;
  private final ProposalLineRepository lines;
  private final Map<AgendaKind, AgendaHeadStore> heads;
  private final WaitService waits;
  private final ActionRequestContext requestContext;

  public ProposalStateStore(
      ProposalRepository proposals,
      ProposalLineRepository lines,
      List<AgendaHeadStore> heads,
      WaitService waits,
      ActionRequestContext requestContext) {
    this.proposals = proposals;
    this.lines = lines;
    this.heads = byKind(heads);
    this.waits = waits;
    this.requestContext = requestContext;
  }

  /**
   * 제안({@code PROPOSE}, 06 MONEY-3)의 저장 방법. 이 요청의 안건과 금액 줄을 들고 있다. {@code ActionPipeline.create}로
   * 연다(새 제안 행을 만드는 행동). 입력 모양은 서비스가 먼저 본다.
   *
   * @param agenda 제안할 안건
   * @param cmd 금액 줄·비용 없음
   */
  public StateStore<ProposalState> proposing(AgendaRef agenda, ProposalCommand cmd) {
    return new Proposing(agenda, cmd);
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

  private final class Proposing implements StateStore<ProposalState> {

    private final AgendaRef agenda;
    private final List<MoneyLine> lineItems;
    private final boolean noCost;

    private Proposing(AgendaRef agenda, ProposalCommand cmd) {
      this.agenda = agenda;
      this.lineItems = cmd.lines() == null ? List.of() : List.copyOf(cmd.lines());
      this.noCost = cmd.noCost();
    }

    /**
     * 처음 요청은 대상이 없어(만드는 행동) 들고 있는 안건의 head와 제안 요약을 읽는다. 다른 호실의 대상이면 {@code NOT_FOUND}(head 저장소). 같은
     * 키로 다시 온 요청은 pipeline이 만든 제안을 대상으로 다시 읽으므로 {@link ProposalStateStore#load}와 같고, 요약은 없다.
     */
    @Override
    public ProposalState load(ActionCommand command) {
      if (command.target() != null) {
        return ProposalStateStore.this.load(command);
      }
      AgendaState head = headOf(agenda.kind()).load(command.roomRecordId(), agenda.targetId());
      long total = noCost ? 0 : lineItems.stream().mapToLong(MoneyLine::amount).sum();
      return new ProposalState(head, waiting(head), new ProposalState.Draft(total, noCost));
    }

    /**
     * 제안 행과 줄을 쓰고, head가 새 안을 가리키게 하고, 상대에게 응답 요청을 연다(03 E "응답 필요"). 시각은 행동 기록과 같은 {@code at()}이다.
     * 회차는 그 안건의 마지막 회차 + 1이다(호실을 잠근 뒤라 겹치지 않음).
     *
     * <p>{@code apply}는 새 안의 ID를 모르므로 head의 기다리는 안은 여기서 채운다. 상태·합의는 {@code apply}가 정한 그대로다.
     */
    @Override
    public SavedState save(ActionCommand command, ProposalState next) {
      UUID roomRecordId = command.roomRecordId();
      PartySide side = command.actor().side();
      Instant at = requestContext.at();
      Proposal proposal = proposals.save(newProposal(roomRecordId, side, at));
      UUID proposalId = proposal.getId();
      for (MoneyLine line : lineItems) {
        lines.save(
            new ProposalLine(
                roomRecordId, proposalId, line.kind(), line.thirdPartyLabel(), line.amount(), at));
      }
      AgendaState decided = next.head();
      headOf(agenda.kind())
          .save(
              new AgendaState(
                  roomRecordId,
                  agenda,
                  decided.status(),
                  proposalId,
                  decided.agreedProposalId(),
                  decided.requiredTotal()));
      waits.open(
          new OpenRequestCommand(roomRecordId, RequestKind.PROPOSAL, requestTarget(agenda), side));
      return SavedState.created(ActionTarget.of(TargetKind.PROPOSAL, proposalId));
    }

    /** 안건 종류마다 제안 행을 만든다. 정산 항목·보증금은 정산 작업(SET-1·2)에서 더한다. */
    private Proposal newProposal(UUID roomRecordId, PartySide side, Instant at) {
      return switch (agenda.kind()) {
        case REPAIR_BURDEN -> {
          int round = proposals.maxRoundOfRepairCase(roomRecordId, agenda.targetId()) + 1;
          yield Proposal.forRepairCase(
              roomRecordId, agenda.targetId(), side, null, null, round, noCost, at);
        }
        case SETTLEMENT_ITEM, DEPOSIT ->
            throw new IllegalStateException("아직 이 안건의 제안은 만들 수 없어요: " + agenda.kind());
      };
    }
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
