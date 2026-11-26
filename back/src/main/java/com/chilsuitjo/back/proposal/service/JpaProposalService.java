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
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.proposal.entity.Proposal;
import com.chilsuitjo.back.proposal.repository.ProposalRepository;
import com.chilsuitjo.back.proposal.repository.ProposalStateStore;
import com.chilsuitjo.back.proposal.rule.ProposalState;
import com.chilsuitjo.back.proposal.rule.ProposalTransitions;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 돈 안건 장치({@link ProposalService}). 행동은 {@link ActionPipeline}을 지나므로 여기에는 {@code @Transactional}을
 * 두지 않는다(09 3-1 트랜잭션 경계).
 *
 * <p><b>아직 다 만들지 않았다</b>: 동의({@link #agree})·이견({@link #dispute})만 있다. {@link #propose}·{@link
 * #rebase}는 {@code null}을 돌려주고, 아직 부르는 곳이 없다.
 */
@Service
@RequiredArgsConstructor
public class JpaProposalService implements ProposalService {

  private final ProposalRepository proposalRepository;
  private final ActionPipeline pipeline;
  private final ProposalStateStore stateStore;
  private final ProposalTransitions rules;

  @Override
  public ProposalResult propose(
      UUID roomRecordId,
      UUID memberId,
      String idempotencyKey,
      AgendaRef agenda,
      ProposalCommand cmd) {
    return null;
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

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
