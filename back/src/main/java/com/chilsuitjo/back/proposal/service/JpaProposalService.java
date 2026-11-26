package com.chilsuitjo.back.proposal.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.ResidentIdGuard;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.dto.AgendaResult;
import com.chilsuitjo.back.proposal.dto.MoneyLine;
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.proposal.entity.Proposal;
import com.chilsuitjo.back.proposal.repository.ProposalRepository;
import com.chilsuitjo.back.proposal.repository.ProposalStateStore;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.LineKind;
import com.chilsuitjo.back.proposal.rule.ProposalState;
import com.chilsuitjo.back.proposal.rule.ProposalTransitions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 돈 안건 장치({@link ProposalService}). 행동은 {@link ActionPipeline}을 지나므로 여기에는 {@code @Transactional}을
 * 두지 않는다(09 3-1 트랜잭션 경계).
 *
 * <p><b>아직 다 만들지 않았다</b>: 제안({@link #propose}, 수리비 부담만)·동의({@link #agree})·이견({@link #dispute})이
 * 있다. {@link #rebase}는 {@code null}을 돌려주고, 아직 부르는 곳이 없다.
 */
@Service
@RequiredArgsConstructor
public class JpaProposalService implements ProposalService {

  /** 수리비 부담 줄의 종류(05 2-4 {@code proposal_line}, {@link LineKind} 주석). */
  private static final Set<LineKind> REPAIR_LINE_KINDS =
      Set.of(LineKind.TENANT, LineKind.LANDLORD, LineKind.THIRD_PARTY);

  /** 제3자 설명 길이(V1 {@code proposal_line.third_party_label varchar(30)}). */
  static final int MAX_THIRD_PARTY_LABEL = 30;

  private final ProposalRepository proposalRepository;
  private final ActionPipeline pipeline;
  private final ProposalStateStore stateStore;
  private final ProposalTransitions rules;

  /**
   * {@inheritDoc}
   *
   * <p>pipeline 전에 입력 모양을 본다({@link #checkShape}, 틀리면 {@code VALIDATION_FAILED}, 제3자 설명이 주민등록번호 형태면
   * {@code RESIDENT_ID_BLOCKED}). 기다리는 안이 있는지·합계는 잠근 뒤 {@code ProposalTransitions}가 본다. 돌려주는 {@link
   * ProposalResult#agenda()}는 {@code null}이다: 만드는 행동이라 응답은 만든 제안 ID만이다(06 4-0).
   */
  @Override
  public ProposalResult propose(
      UUID roomRecordId,
      UUID memberId,
      String idempotencyKey,
      AgendaRef agenda,
      ProposalCommand cmd) {
    ProposalCommand checked = checkShape(agenda, cmd);
    PipelineResult<ProposalState> result =
        pipeline.create(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                ActionType.PROPOSE,
                idempotencyKey,
                null,
                null,
                null,
                null,
                Map.of()),
            stateStore.proposing(agenda, checked),
            rules);
    return new ProposalResult(result.action().target().id(), null, result.action().replayed());
  }

  @Override
  public AgendaResult rebase(UUID roomRecordId, AgendaRef agenda, long newRequiredTotal) {
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>잠글 호실은 제안 행에서 찾는다. 제안은 덧붙이기만 하는 행이라 잠금 전에 읽어도 된다. 남의 호실 제안이면 pipeline 잠금의 당사자 확인에서 막힌다. 측·지금
   * 안인지·합계는 잠근 뒤 {@code ProposalTransitions}가 본다.
   */
  @Override
  public ProposalResult agree(UUID proposalId, UUID memberId, String idempotencyKey) {
    return answer(proposalId, memberId, idempotencyKey, ActionType.AGREE, null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>사유는 pipeline 전에 주민등록번호 형태를 막고({@code RESIDENT_ID_BLOCKED}, 03 6-1), 공백뿐이면 없는 것으로 넘긴다. 사유가 없을
   * 때의 {@code REASON_REQUIRED}는 잠근 뒤 {@code ProposalTransitions}가 본다. 나머지는 {@link #agree}와 같다.
   */
  @Override
  public ProposalResult dispute(
      UUID proposalId, UUID memberId, String idempotencyKey, String reason) {
    ResidentIdGuard.check(reason);
    return answer(
        proposalId, memberId, idempotencyKey, ActionType.DISPUTE_PROPOSAL, blankToNull(reason));
  }

  /** 기다리는 안에 대한 상대의 답(동의·이견). 대상은 제안이고 새 기록을 만들지 않는다. */
  private ProposalResult answer(
      UUID proposalId, UUID memberId, String idempotencyKey, ActionType type, String reason) {
    UUID roomRecordId =
        proposalRepository
            .findById(proposalId)
            .map(Proposal::getRoomRecordId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    PipelineResult<ProposalState> result =
        pipeline.execute(
            new ActionCommand(
                roomRecordId,
                Actor.member(memberId),
                type,
                idempotencyKey,
                ActionTarget.of(TargetKind.PROPOSAL, proposalId),
                null,
                null,
                reason,
                Map.of()),
            stateStore,
            rules);
    return new ProposalResult(proposalId, null, result.action().replayed());
  }

  /**
   * 안건 종류에 맞는 제안 모양인지 본다(D-2a: 돈 안건 장치의 규칙이라 여기서 본다). 수리비 부담은:
   *
   * <ul>
   *   <li>비용 없음이면 줄이 없고, 아니면 줄이 하나 이상(03 E "부담 안의 모양")
   *   <li>줄 종류는 집주인·세입자·제3자, 금액은 0 이상, 합이 {@code long}을 넘지 않음. 한 줄이 0원인 것은 받지만 합계가 0원이면 거절한다(비용
   *       없음으로 제안)
   *   <li>제3자 줄에만 설명이 있고 꼭 있으며 30자 이하(03 E "제3자는 짧은 설명만", V1). 주민등록번호 형태는 막는다(03 6-1)
   *   <li>빼기·사유는 정산 항목에만 있다(05 {@code proposal.reason}). 빈 사유는 없는 것으로 본다
   * </ul>
   *
   * @return 빈 사유를 비운 명령
   */
  private static ProposalCommand checkShape(AgendaRef agenda, ProposalCommand cmd) {
    if (agenda.kind() != AgendaKind.REPAIR_BURDEN) {
      throw new IllegalStateException("아직 이 안건의 제안은 받지 않아요: " + agenda.kind());
    }
    if (cmd == null || cmd.exclude() || blankToNull(cmd.reason()) != null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    List<MoneyLine> lines = cmd.lines() == null ? List.of() : cmd.lines();
    if (cmd.noCost() == !lines.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    long total = 0;
    for (MoneyLine line : lines) {
      // Set.of는 null을 물으면 예외라 종류가 없는지 먼저 본다
      if (line == null
          || line.kind() == null
          || !REPAIR_LINE_KINDS.contains(line.kind())
          || line.amount() < 0) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      String label = line.thirdPartyLabel();
      if (line.kind() == LineKind.THIRD_PARTY) {
        if (label == null || label.isBlank() || label.length() > MAX_THIRD_PARTY_LABEL) {
          throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ResidentIdGuard.check(label);
      } else if (label != null) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      try {
        total = Math.addExact(total, line.amount());
      } catch (ArithmeticException overflow) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
    }
    // 돈이 들지 않는 건은 비용 없음으로만 남긴다: 0원 줄 안이 합의되면 NO_COST와 같은 뜻의 AGREED가 생김(decisions 2026-11-26)
    if (!cmd.noCost() && total == 0) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new ProposalCommand(lines, cmd.noCost(), false, null);
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
