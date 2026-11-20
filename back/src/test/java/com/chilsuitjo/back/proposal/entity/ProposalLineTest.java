package com.chilsuitjo.back.proposal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.proposal.rule.LineKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 제안 줄이 V1 CHECK(금액 0 이상, 관계 설명은 THIRD_PARTY 줄에만)를 자바에서 먼저 지키는지 본다. */
class ProposalLineTest {

  private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
  private static final UUID ROOM = UUID.randomUUID();
  private static final UUID PROPOSAL = UUID.randomUUID();

  @Test
  void 금액이_0이면_만든다() {
    ProposalLine line = new ProposalLine(ROOM, PROPOSAL, LineKind.TENANT, null, 0, NOW);

    assertThat(line.getAmount()).isZero();
  }

  @Test
  void 금액이_음수면_거절한다() {
    assertThatThrownBy(() -> new ProposalLine(ROOM, PROPOSAL, LineKind.TENANT, null, -1, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 제3자_줄은_관계_설명을_담는다() {
    ProposalLine line = new ProposalLine(ROOM, PROPOSAL, LineKind.THIRD_PARTY, "윗집", 30_000, NOW);

    assertThat(line.getLineKind()).isEqualTo(LineKind.THIRD_PARTY);
    assertThat(line.getThirdPartyLabel()).isEqualTo("윗집");
    assertThat(line.getAmount()).isEqualTo(30_000);
  }

  @ParameterizedTest
  @EnumSource(value = LineKind.class, names = "THIRD_PARTY", mode = EnumSource.Mode.EXCLUDE)
  void 제3자가_아닌_줄에_관계_설명이_있으면_거절한다(LineKind kind) {
    assertThatThrownBy(() -> new ProposalLine(ROOM, PROPOSAL, kind, "윗집", 10_000, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(value = LineKind.class, names = "THIRD_PARTY", mode = EnumSource.Mode.EXCLUDE)
  void 제3자가_아닌_줄은_관계_설명_없이_만든다(LineKind kind) {
    ProposalLine line = new ProposalLine(ROOM, PROPOSAL, kind, null, 10_000, NOW);

    assertThat(line.getThirdPartyLabel()).isNull();
  }
}
