package com.chilsuitjo.back.proposal.service;

import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.dto.AgendaResult;
import com.chilsuitjo.back.proposal.dto.MoneyLine;
import com.chilsuitjo.back.proposal.dto.ProposalCommand;
import com.chilsuitjo.back.proposal.dto.ProposalItemResult;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import com.chilsuitjo.back.proposal.rule.ProposalOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * 돈 안건 장치 가짜(#111). DB·pipeline 없이 메모리에 안건만 적는다. repair·settlement가 {@link ProposalService}를 부르는
 * 모양과 첫 제안·합의 뒤 새 제안·{@link #rebase}의 결과는 진짜와 같다.
 *
 * <p>진짜와 다른 것: 회원의 측은 pipeline 대신 {@link #seedParty}로 적은 값을 쓴다. 동의는 행동이 아니라 {@link #agree}로 정한다.
 * 기다리는 안이 있을 때의 새 제안(바꾸기·역제안)은 2주차 "돈 안건 장치 완성"에서 만들고, 가짜는 {@code INVALID_STATE}로 거절한다.
 *
 * <p>빈이 아니다: {@link ProposalService} 빈은 {@link JpaProposalService}다. 테스트가 {@code new}로 만들어 쓴다. 실제
 * 구현이 다 들어오면 이 클래스를 지운다. 다른 도메인은 {@link ProposalService}만 쓰므로 바뀌지 않는다.
 */
public class FakeProposalService implements ProposalService {

  private final Clock clock;
  private final Map<Party, PartySide> sides = new ConcurrentHashMap<>();
  private final Map<Agenda, Long> requiredTotals = new ConcurrentHashMap<>();
  private final Map<Agenda, AgendaResult> agendas = new ConcurrentHashMap<>();
  private final Map<Replay, ProposalResult> replays = new ConcurrentHashMap<>();

  public FakeProposalService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public ProposalResult propose(
      UUID roomRecordId,
      UUID memberId,
      String idempotencyKey,
      AgendaRef agenda,
      ProposalCommand cmd) {
    Replay replay = new Replay(roomRecordId, memberId, idempotencyKey);
    ProposalResult first = replays.get(replay);
    if (first != null) {
      return new ProposalResult(first.proposalId(), first.agenda(), true);
    }
    PartySide side = sides.get(new Party(roomRecordId, memberId));
    if (side == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Agenda key = new Agenda(roomRecordId, agenda);
    AgendaResult now = agendas.getOrDefault(key, empty(agenda));
    if (now.waiting() != null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    List<MoneyLine> lines = cmd.lines() == null ? List.of() : List.copyOf(cmd.lines());
    long total = cmd.noCost() ? 0 : lines.stream().mapToLong(MoneyLine::amount).sum();
    Long required = requiredTotals.get(key);
    if (!cmd.noCost() && required != null && total != required) {
      throw new BusinessException(ErrorCode.SUM_MISMATCH);
    }
    ProposalItemResult item =
        new ProposalItemResult(
            UUID.randomUUID(),
            side,
            now.round() + 1,
            null,
            null,
            lines,
            cmd.noCost(),
            cmd.exclude(),
            cmd.reason(),
            total,
            clock.instant(),
            ProposalOutcome.WAITING,
            null);
    AgendaStatus status = now.agreed() != null ? now.status() : AgendaStatus.PENDING;
    AgendaResult next =
        new AgendaResult(
            agenda,
            status,
            now.agreed(),
            item,
            now.previousAgreed(),
            now.baseChanged(),
            item.round(),
            append(now.history(), item));
    agendas.put(key, next);
    ProposalResult result = new ProposalResult(item.id(), next, false);
    replays.put(replay, result);
    return result;
  }

  @Override
  public AgendaResult rebase(UUID roomRecordId, AgendaRef agenda, long newRequiredTotal) {
    Agenda key = new Agenda(roomRecordId, agenda);
    requiredTotals.put(key, newRequiredTotal);
    AgendaResult now = agendas.get(key);
    if (now == null) {
      return empty(agenda);
    }
    if (now.agreed() == null || now.agreed().total() == newRequiredTotal) {
      return now;
    }
    List<ProposalItemResult> history = now.history();
    if (now.waiting() != null) {
      history =
          replace(
              history,
              withOutcome(now.waiting(), ProposalOutcome.CLOSED, ClosedReason.AMOUNT_CHANGED));
    }
    AgendaResult next =
        new AgendaResult(
            agenda, AgendaStatus.PENDING, null, null, now.agreed(), true, now.round(), history);
    agendas.put(key, next);
    return next;
  }

  /** 이 호실의 이 측 회원으로 적어 둔다. 적지 않은 회원의 제안은 {@code NOT_FOUND}(진짜는 pipeline이 거절). */
  public void seedParty(UUID roomRecordId, UUID memberId, PartySide side) {
    sides.put(new Party(roomRecordId, memberId), side);
  }

  /** 수리비 부담 안건의 같이 확인된 지불 합. 적어 두면 줄 금액의 합이 다른 제안을 {@code SUM_MISMATCH}로 거절한다. */
  public void seedRequiredTotal(UUID roomRecordId, AgendaRef agenda, long total) {
    requiredTotals.put(new Agenda(roomRecordId, agenda), total);
  }

  /** 가짜는 제안 ID로 답하지 않는다. 테스트는 {@link #agree(UUID, AgendaRef)}로 동의를 정한다. */
  @Override
  public ProposalResult agree(UUID proposalId, UUID memberId, String idempotencyKey) {
    throw new UnsupportedOperationException("가짜에는 제안 ID로 동의하는 길이 없어요");
  }

  /** 가짜에는 이견이 없다. */
  @Override
  public ProposalResult dispute(
      UUID proposalId, UUID memberId, String idempotencyKey, String reason) {
    throw new UnsupportedOperationException("가짜에는 이견이 없어요");
  }

  /** 기다리는 안에 상대가 동의한 것으로 정한다. 기다리는 안이 없으면 {@code INVALID_STATE}. */
  public AgendaResult agree(UUID roomRecordId, AgendaRef agenda) {
    Agenda key = new Agenda(roomRecordId, agenda);
    AgendaResult now = agendas.getOrDefault(key, empty(agenda));
    if (now.waiting() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    ProposalItemResult agreed = withOutcome(now.waiting(), ProposalOutcome.AGREED, null);
    AgendaStatus status = agreed.noCost() ? AgendaStatus.NO_COST : AgendaStatus.AGREED;
    AgendaResult next =
        new AgendaResult(
            agenda,
            status,
            agreed,
            null,
            now.previousAgreed(),
            false,
            now.round(),
            replace(now.history(), agreed));
    agendas.put(key, next);
    return next;
  }

  /** 가짜는 행동을 남기지 않아 보이는 범위를 보지 않고 지금 상태를 돌려준다. */
  @Override
  public AgendaStatus statusAsSeen(
      UUID roomRecordId, AgendaRef agenda, BiPredicate<PartySide, Instant> sees) {
    return agendas.getOrDefault(new Agenda(roomRecordId, agenda), empty(agenda)).status();
  }

  private static AgendaResult empty(AgendaRef agenda) {
    return new AgendaResult(agenda, AgendaStatus.NONE, null, null, null, false, 0, List.of());
  }

  private static ProposalItemResult withOutcome(
      ProposalItemResult item, ProposalOutcome outcome, ClosedReason closedReason) {
    return new ProposalItemResult(
        item.id(),
        item.proposedBy(),
        item.round(),
        item.counterOfId(),
        item.replacesId(),
        item.lines(),
        item.noCost(),
        item.exclude(),
        item.reason(),
        item.total(),
        item.createdAt(),
        outcome,
        closedReason);
  }

  private static List<ProposalItemResult> append(
      List<ProposalItemResult> history, ProposalItemResult item) {
    List<ProposalItemResult> next = new ArrayList<>(history);
    next.add(item);
    return List.copyOf(next);
  }

  private static List<ProposalItemResult> replace(
      List<ProposalItemResult> history, ProposalItemResult item) {
    return history.stream().map(h -> h.id().equals(item.id()) ? item : h).toList();
  }

  private record Party(UUID roomRecordId, UUID memberId) {}

  private record Agenda(UUID roomRecordId, AgendaRef agenda) {}

  private record Replay(UUID roomRecordId, UUID memberId, String idempotencyKey) {}
}
