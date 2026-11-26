package com.chilsuitjo.back.proposal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.proposal.dto.AgendaRef;
import com.chilsuitjo.back.proposal.rule.AgendaKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 제안 팩토리가 V1 CHECK(대상 칸 하나, {@code round >= 1})를 자바에서 먼저 지키는지, 채운 대상 칸으로 안건({@code agenda()})을 정하는지
 * 본다.
 */
class ProposalTest {

  private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
  private static final UUID ROOM = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  @Test
  void 수리_건_부담_제안은_수리_건_칸만_채운다() {
    Proposal proposal =
        Proposal.forRepairCase(ROOM, TARGET, PartySide.TENANT, null, null, 1, false, NOW);

    assertThat(proposal.getRepairCaseId()).isEqualTo(TARGET);
    assertThat(proposal.getSettlementItemId()).isNull();
    assertThat(proposal.getSettlementId()).isNull();
    assertThat(proposal.isExclude()).isFalse();
    assertThat(proposal.getReason()).isNull();
  }

  @Test
  void 정산_항목_제안은_정산_항목_칸만_채우고_빼기와_사유를_담는다() {
    Proposal proposal =
        Proposal.forSettlementItem(
            ROOM, TARGET, PartySide.LANDLORD, null, null, 1, true, "이미 고친 자리예요", NOW);

    assertThat(proposal.getRepairCaseId()).isNull();
    assertThat(proposal.getSettlementItemId()).isEqualTo(TARGET);
    assertThat(proposal.getSettlementId()).isNull();
    assertThat(proposal.isExclude()).isTrue();
    assertThat(proposal.isNoCost()).isFalse();
    assertThat(proposal.getReason()).isEqualTo("이미 고친 자리예요");
  }

  @Test
  void 보증금_제안은_정산_칸만_채운다() {
    Proposal proposal = Proposal.forDeposit(ROOM, TARGET, PartySide.LANDLORD, null, null, 1, NOW);

    assertThat(proposal.getRepairCaseId()).isNull();
    assertThat(proposal.getSettlementItemId()).isNull();
    assertThat(proposal.getSettlementId()).isEqualTo(TARGET);
    assertThat(proposal.isNoCost()).isFalse();
    assertThat(proposal.isExclude()).isFalse();
    assertThat(proposal.getReason()).isNull();
  }

  @Test
  void 수리_건_없이_부담_제안을_만들면_거절한다() {
    assertThatThrownBy(
            () -> Proposal.forRepairCase(ROOM, null, PartySide.TENANT, null, null, 1, false, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void 정산_항목_없이_정산_항목_제안을_만들면_거절한다() {
    assertThatThrownBy(() ->
            Proposal.forSettlementItem(
                ROOM, null, PartySide.TENANT, null, null, 1, false, null, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void 정산_없이_보증금_제안을_만들면_거절한다() {
    assertThatThrownBy(() -> Proposal.forDeposit(ROOM, null, PartySide.TENANT, null, null, 1, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void round가_1보다_작으면_거절한다() {
    assertThatThrownBy(
            () -> Proposal.forRepairCase(ROOM, TARGET, PartySide.TENANT, null, null, 0, false, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void round가_1이면_만든다() {
    Proposal proposal =
        Proposal.forRepairCase(ROOM, TARGET, PartySide.TENANT, null, null, 1, true, NOW);

    assertThat(proposal.getRound()).isEqualTo(1);
    assertThat(proposal.isNoCost()).isTrue();
  }

  // ---- 붙은 안건(agenda) ----

  @Test
  void 수리_건_제안은_수리비_부담_안건이다() {
    Proposal proposal =
        Proposal.forRepairCase(ROOM, TARGET, PartySide.TENANT, null, null, 1, false, NOW);

    assertThat(proposal.agenda()).isEqualTo(new AgendaRef(AgendaKind.REPAIR_BURDEN, TARGET));
  }

  @Test
  void 정산_항목_제안은_정산_항목_안건이다() {
    Proposal proposal =
        Proposal.forSettlementItem(
            ROOM, TARGET, PartySide.LANDLORD, null, null, 1, false, "도배", NOW);

    assertThat(proposal.agenda()).isEqualTo(new AgendaRef(AgendaKind.SETTLEMENT_ITEM, TARGET));
  }

  @Test
  void 보증금_제안은_보증금_안건이다() {
    Proposal proposal = Proposal.forDeposit(ROOM, TARGET, PartySide.TENANT, null, null, 1, NOW);

    assertThat(proposal.agenda()).isEqualTo(new AgendaRef(AgendaKind.DEPOSIT, TARGET));
  }
}
