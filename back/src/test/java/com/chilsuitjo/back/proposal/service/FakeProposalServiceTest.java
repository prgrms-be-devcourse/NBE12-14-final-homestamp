package com.chilsuitjo.back.proposal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import com.chilsuitjo.back.proposal.rule.AgendaStatus;
import com.chilsuitjo.back.proposal.rule.LineKind;
import com.chilsuitjo.back.proposal.rule.ProposalOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeProposalServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z");

  private final FakeProposalService proposals =
      new FakeProposalService(Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();
  private final UUID landlord = UUID.randomUUID();
  private final AgendaRef burden = new AgendaRef(AgendaKind.REPAIR_BURDEN, UUID.randomUUID());

  @BeforeEach
  void setUp() {
    proposals.seedParty(roomRecordId, tenant, PartySide.TENANT);
    proposals.seedParty(roomRecordId, landlord, PartySide.LANDLORD);
  }

  @Test
  void 첫_제안은_기다리는_안이_된다() {
    ProposalResult result = proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));

    AgendaResult agenda = result.agenda();
    assertThat(result.replayed()).isFalse();
    assertThat(agenda.agenda()).isEqualTo(burden);
    assertThat(agenda.status()).isEqualTo(AgendaStatus.PENDING);
    assertThat(agenda.agreed()).isNull();
    assertThat(agenda.round()).isEqualTo(1);
    ProposalItemResult waiting = agenda.waiting();
    assertThat(waiting.id()).isEqualTo(result.proposalId());
    assertThat(waiting.proposedBy()).isEqualTo(PartySide.TENANT);
    assertThat(waiting.total()).isEqualTo(82_000);
    assertThat(waiting.createdAt()).isEqualTo(NOW);
    assertThat(waiting.outcome()).isEqualTo(ProposalOutcome.WAITING);
    assertThat(agenda.history()).containsExactly(waiting);
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려준다() {
    ProposalResult first = proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));

    ProposalResult again = proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));

    assertThat(again.replayed()).isTrue();
    assertThat(again.proposalId()).isEqualTo(first.proposalId());
    assertThat(again.agenda()).isEqualTo(first.agenda());
  }

  @Test
  void 호실의_당사자가_아니면_NOT_FOUND() {
    assertError(
        () -> proposals.propose(roomRecordId, UUID.randomUUID(), "k1", burden, split(1, 0)),
        ErrorCode.NOT_FOUND);
  }

  @Test
  void 줄_금액의_합이_확인된_지불의_합과_다르면_SUM_MISMATCH() {
    proposals.seedRequiredTotal(roomRecordId, burden, 82_000);

    assertError(
        () -> proposals.propose(roomRecordId, tenant, "k1", burden, split(60_000, 20_000)),
        ErrorCode.SUM_MISMATCH);
  }

  @Test
  void 비용_없음_제안은_합을_보지_않는다() {
    proposals.seedRequiredTotal(roomRecordId, burden, 82_000);

    ProposalResult result =
        proposals.propose(
            roomRecordId, tenant, "k1", burden, new ProposalCommand(null, true, false, null));

    assertThat(result.agenda().waiting().noCost()).isTrue();
    assertThat(result.agenda().waiting().total()).isZero();
    assertThat(result.agenda().waiting().lines()).isEmpty();
  }

  @Test
  void 기다리는_안이_있으면_새_제안은_INVALID_STATE() {
    proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));

    assertError(
        () -> proposals.propose(roomRecordId, landlord, "k2", burden, split(60_000, 22_000)),
        ErrorCode.INVALID_STATE);
  }

  @Test
  void 동의하면_합의가_된다() {
    ProposalResult proposed =
        proposals.propose(roomRecordId, tenant, "k1", burden, split(60_000, 22_000));

    AgendaResult agreed = proposals.agree(roomRecordId, burden);

    assertThat(agreed.status()).isEqualTo(AgendaStatus.AGREED);
    assertThat(agreed.waiting()).isNull();
    assertThat(agreed.agreed().id()).isEqualTo(proposed.proposalId());
    assertThat(agreed.agreed().outcome()).isEqualTo(ProposalOutcome.AGREED);
    assertThat(agreed.history()).containsExactly(agreed.agreed());
  }

  @Test
  void 비용_없음에_동의하면_NO_COST() {
    proposals.propose(
        roomRecordId, tenant, "k1", burden, new ProposalCommand(List.of(), true, false, null));

    assertThat(proposals.agree(roomRecordId, burden).status()).isEqualTo(AgendaStatus.NO_COST);
  }

  @Test
  void 기다리는_안이_없으면_동의는_INVALID_STATE() {
    assertError(() -> proposals.agree(roomRecordId, burden), ErrorCode.INVALID_STATE);
  }

  @Test
  void 합의_뒤_새_제안이_와도_합의는_그대로다() {
    proposals.propose(roomRecordId, tenant, "k1", burden, split(60_000, 22_000));
    AgendaResult agreed = proposals.agree(roomRecordId, burden);

    ProposalResult change =
        proposals.propose(roomRecordId, landlord, "k2", burden, split(50_000, 32_000));

    AgendaResult agenda = change.agenda();
    assertThat(agenda.status()).isEqualTo(AgendaStatus.AGREED);
    assertThat(agenda.agreed()).isEqualTo(agreed.agreed());
    assertThat(agenda.waiting().id()).isEqualTo(change.proposalId());
    assertThat(agenda.waiting().proposedBy()).isEqualTo(PartySide.LANDLORD);
    assertThat(agenda.round()).isEqualTo(2);
    assertThat(agenda.history()).hasSize(2);
  }

  @Test
  void 확인된_합이_바뀌면_합의가_풀리고_기다리던_안은_닫힌다() {
    proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));
    AgendaResult agreed = proposals.agree(roomRecordId, burden);
    ProposalResult change =
        proposals.propose(roomRecordId, landlord, "k2", burden, split(50_000, 32_000));

    AgendaResult rebased = proposals.rebase(roomRecordId, burden, 92_000);

    assertThat(rebased.status()).isEqualTo(AgendaStatus.PENDING);
    assertThat(rebased.agreed()).isNull();
    assertThat(rebased.waiting()).isNull();
    assertThat(rebased.previousAgreed()).isEqualTo(agreed.agreed());
    assertThat(rebased.baseChanged()).isTrue();
    assertThat(rebased.history())
        .filteredOn(p -> p.id().equals(change.proposalId()))
        .singleElement()
        .satisfies(p -> {
          assertThat(p.outcome()).isEqualTo(ProposalOutcome.CLOSED);
          assertThat(p.closedReason()).isEqualTo(ClosedReason.AMOUNT_CHANGED);
        });
  }

  @Test
  void 기다리던_안_없이_합의만_있어도_합의가_풀린다() {
    proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));
    proposals.agree(roomRecordId, burden);

    AgendaResult rebased = proposals.rebase(roomRecordId, burden, 92_000);

    assertThat(rebased.status()).isEqualTo(AgendaStatus.PENDING);
    assertThat(rebased.baseChanged()).isTrue();
  }

  @Test
  void 합의한_합과_같으면_rebase는_바꾸지_않는다() {
    proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));
    AgendaResult agreed = proposals.agree(roomRecordId, burden);

    assertThat(proposals.rebase(roomRecordId, burden, 82_000)).isEqualTo(agreed);
  }

  @Test
  void 합의가_없으면_rebase는_바꾸지_않는다() {
    AgendaResult waiting =
        proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0)).agenda();

    assertThat(proposals.rebase(roomRecordId, burden, 92_000)).isEqualTo(waiting);
  }

  @Test
  void 제안이_없는_안건의_rebase는_빈_안건이고_새_합계로_검사한다() {
    AgendaResult rebased = proposals.rebase(roomRecordId, burden, 92_000);

    assertThat(rebased.status()).isEqualTo(AgendaStatus.NONE);
    assertThat(rebased.history()).isEmpty();
    assertError(
        () -> proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0)),
        ErrorCode.SUM_MISMATCH);
  }

  @Test
  void 안건마다_따로_적는다() {
    AgendaRef other = new AgendaRef(AgendaKind.REPAIR_BURDEN, UUID.randomUUID());
    proposals.propose(roomRecordId, tenant, "k1", burden, split(82_000, 0));

    ProposalResult result = proposals.propose(roomRecordId, tenant, "k2", other, split(10_000, 0));

    assertThat(result.agenda().round()).isEqualTo(1);
    assertThat(result.agenda().agenda()).isEqualTo(other);
  }

  private static ProposalCommand split(long landlordPays, long tenantPays) {
    return new ProposalCommand(
        List.of(
            new MoneyLine(LineKind.LANDLORD, null, landlordPays),
            new MoneyLine(LineKind.TENANT, null, tenantPays)),
        false,
        false,
        null);
  }

  private static void assertError(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(code);
  }
}
