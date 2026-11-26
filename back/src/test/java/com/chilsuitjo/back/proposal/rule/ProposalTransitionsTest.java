package com.chilsuitjo.back.proposal.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 돈 안건 동의·이견 규칙(03 1-2·E, 06 MONEY-3). 순수 함수라 pipeline 없이 부른다(안내서 5-1).
 *
 * <p>기대값: 06 3장 MONEY-3a·3c, 06 4-6 {@code /api/proposals/{id}/agree}·{@code /dispute} 오류 칸, 03 2장
 * E 상태 그림, 03 1-2 "합의 뒤 새 제안", 03 5장 불변식 2, decisions 2026-11-19(이견 사유 필수).
 */
class ProposalTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-26T00:00:00Z");
  private static final UUID ROOM = UUID.randomUUID();
  private static final AgendaRef AGENDA =
      new AgendaRef(AgendaKind.REPAIR_BURDEN, UUID.randomUUID());

  private final ProposalTransitions rules = new ProposalTransitions();

  @Test
  @Tag("invariant-02")
  void 상대가_기다리는_안에_동의하면_합의된다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    ProposalState next = rules.apply(now, agree(PartySide.TENANT, proposal));

    assertThat(next.head())
        .isEqualTo(new AgendaState(ROOM, AGENDA, AgendaStatus.AGREED, null, proposal, 82_000L));
    assertThat(next.waiting()).isNull();
  }

  @Test
  void 비용_없음_안에_동의하면_비용_없음이_된다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 0, true, null, 0L);

    ProposalState next = rules.apply(now, agree(PartySide.TENANT, proposal));

    assertThat(next.head())
        .isEqualTo(new AgendaState(ROOM, AGENDA, AgendaStatus.NO_COST, null, proposal, 0L));
    assertThat(next.waiting()).isNull();
  }

  @Test
  void 합의_뒤_바꾸자는_안에_동의하면_새_합의가_된다() {
    UUID agreed = UUID.randomUUID();
    UUID change = UUID.randomUUID();
    ProposalState now =
        new ProposalState(
            new AgendaState(ROOM, AGENDA, AgendaStatus.AGREED, change, agreed, 82_000L),
            new ProposalState.Waiting(change, PartySide.LANDLORD, 82_000, false));

    ProposalState next = rules.apply(now, agree(PartySide.TENANT, change));

    assertThat(next.head())
        .isEqualTo(new AgendaState(ROOM, AGENDA, AgendaStatus.AGREED, null, change, 82_000L));
  }

  @Test
  @Tag("invariant-02")
  void MONEY_3c_내_제안에는_내가_동의할_수_없다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    assertError(
        () -> rules.apply(now, agree(PartySide.LANDLORD, proposal)), ErrorCode.OWN_PROPOSAL);
  }

  @Test
  void MONEY_3a_나눈_금액의_합이_전체_금액과_다르면_동의할_수_없다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 92_000L);

    assertError(() -> rules.apply(now, agree(PartySide.TENANT, proposal)), ErrorCode.SUM_MISMATCH);
  }

  @Test
  void 기다리는_안이_아닌_제안에는_동의할_수_없다() {
    UUID waiting = UUID.randomUUID();
    UUID older = UUID.randomUUID();
    ProposalState now = waitingFor(waiting, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    assertError(
        () -> rules.apply(now, agree(PartySide.TENANT, older)), ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  @Test
  void 미정에서는_기다리는_안이_없어_동의할_수_없다() {
    ProposalState now =
        new ProposalState(
            new AgendaState(ROOM, AGENDA, AgendaStatus.NONE, null, null, 82_000L), null);

    assertError(
        () -> rules.apply(now, agree(PartySide.TENANT, UUID.randomUUID())),
        ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  @Test
  void 이견_상태에서는_기다리는_안이_없어_동의할_수_없다() {
    ProposalState now =
        new ProposalState(
            new AgendaState(ROOM, AGENDA, AgendaStatus.DISPUTE, null, null, 82_000L), null);

    assertError(
        () -> rules.apply(now, agree(PartySide.TENANT, UUID.randomUUID())),
        ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  // ---- 이견(DISPUTE_PROPOSAL) ----

  @Test
  void 상대가_기다리는_안에_사유와_함께_이견을_남기면_이견이_된다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    ProposalState next = rules.apply(now, dispute(PartySide.TENANT, proposal, "제 잘못이 아니에요"));

    assertThat(next.head())
        .isEqualTo(new AgendaState(ROOM, AGENDA, AgendaStatus.DISPUTE, null, null, 82_000L));
    assertThat(next.waiting()).isNull();
  }

  @Test
  void 합의_뒤_바꾸자는_안에_이견을_남기면_합의는_그대로다() {
    UUID agreed = UUID.randomUUID();
    UUID change = UUID.randomUUID();
    ProposalState now = waitingFor(change, PartySide.LANDLORD, 50_000, false, agreed, 82_000L);

    ProposalState next = rules.apply(now, dispute(PartySide.TENANT, change, "처음 합의대로 해요"));

    assertThat(next.head())
        .isEqualTo(new AgendaState(ROOM, AGENDA, AgendaStatus.AGREED, null, agreed, 82_000L));
    assertThat(next.waiting()).isNull();
  }

  @Test
  void 이견에는_사유가_있어야_한다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    assertError(
        () -> rules.apply(now, dispute(PartySide.TENANT, proposal, null)),
        ErrorCode.REASON_REQUIRED);
  }

  @Test
  void 내_제안에는_내가_이견을_남길_수_없다() {
    UUID proposal = UUID.randomUUID();
    ProposalState now = waitingFor(proposal, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    assertError(
        () -> rules.apply(now, dispute(PartySide.LANDLORD, proposal, "다시 생각해 보니")),
        ErrorCode.OWN_PROPOSAL);
  }

  @Test
  void 기다리는_안이_아닌_제안에는_이견을_남길_수_없다() {
    UUID waiting = UUID.randomUUID();
    ProposalState now = waitingFor(waiting, PartySide.LANDLORD, 82_000, false, null, 82_000L);

    assertError(
        () -> rules.apply(now, dispute(PartySide.TENANT, UUID.randomUUID(), "달라요")),
        ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  @Test
  void 미정에서는_기다리는_안이_없어_이견을_남길_수_없다() {
    ProposalState now =
        new ProposalState(
            new AgendaState(ROOM, AGENDA, AgendaStatus.NONE, null, null, 82_000L), null);

    assertError(
        () -> rules.apply(now, dispute(PartySide.TENANT, UUID.randomUUID(), "달라요")),
        ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  @Test
  void 이견_상태에서는_기다리는_안이_없어_다시_이견을_남길_수_없다() {
    ProposalState now =
        new ProposalState(
            new AgendaState(ROOM, AGENDA, AgendaStatus.DISPUTE, null, null, 82_000L), null);

    assertError(
        () -> rules.apply(now, dispute(PartySide.TENANT, UUID.randomUUID(), "달라요")),
        ErrorCode.NOT_CURRENT_PROPOSAL);
  }

  /** 합의 대기: head가 이 안을 가리키고 아직 합의가 없다(또는 {@code agreed}가 있다). */
  private static ProposalState waitingFor(
      UUID proposal,
      PartySide proposedBy,
      long total,
      boolean noCost,
      UUID agreed,
      Long requiredTotal) {
    AgendaStatus status = agreed == null ? AgendaStatus.PENDING : AgendaStatus.AGREED;
    return new ProposalState(
        new AgendaState(ROOM, AGENDA, status, proposal, agreed, requiredTotal),
        new ProposalState.Waiting(proposal, proposedBy, total, noCost));
  }

  private static ActionEntry agree(PartySide side, UUID proposal) {
    return entry(ActionType.AGREE, side, proposal, null);
  }

  /** 이견 사유는 행동 기록의 {@code reason}에 남는다(decisions 2026-11-19). */
  private static ActionEntry dispute(PartySide side, UUID proposal, String reason) {
    return entry(ActionType.DISPUTE_PROPOSAL, side, proposal, reason);
  }

  private static ActionEntry entry(ActionType type, PartySide side, UUID proposal, String reason) {
    return new ActionEntry(
        null,
        ROOM,
        0,
        type,
        side,
        "k",
        null,
        null,
        ActionTarget.of(TargetKind.PROPOSAL, proposal),
        null,
        null,
        reason,
        Map.of(),
        NOW);
  }

  private static void assertError(Runnable call, ErrorCode expected) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(expected));
  }
}
