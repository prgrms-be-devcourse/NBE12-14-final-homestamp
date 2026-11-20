package com.chilsuitjo.back.settlement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.settlement.rule.DepositStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 정산을 열 때의 처음 상태와 필수 칸을 본다. */
class SettlementTest {

  private static final UUID ROOM = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  @Test
  void 정산을_열면_보증금_확인_전이고_정산_모습은_1번이다() {
    Settlement settlement = new Settlement(ROOM, NOW);

    assertThat(settlement.getDepositStatus()).isEqualTo(DepositStatus.NOT_ENTERED);
    assertThat(settlement.getSnapshotNo()).isEqualTo(1);
    assertThat(settlement.getCurrentDepositProposalId()).isNull();
    assertThat(settlement.getAgreedDepositProposalId()).isNull();
  }

  @Test
  void 호실_기록이_없으면_막는다() {
    assertThatThrownBy(() -> new Settlement(null, NOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("정산에는 호실 기록이 있어야 해요");
  }

  @Test
  void 생성_시각이_없으면_막는다() {
    assertThatThrownBy(() -> new Settlement(ROOM, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("정산에는 생성 시각이 있어야 해요");
  }
}
