package com.chilsuitjo.back.settlement.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 정산 규칙(03 I, 06 SET-1)을 순수 함수로 본다. */
class SettlementTransitionsTest {

  private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

  private final SettlementTransitions rules = new SettlementTransitions();

  @Test
  void 처음_상태는_열리지_않은_정산이다() {
    assertThat(rules.initial()).isEqualTo(SettlementState.NONE);
    assertThat(rules.initial().opened()).isFalse();
  }

  @Test
  void 열지_않은_정산을_열면_열린_정산이다() {
    SettlementState next =
        rules.apply(SettlementState.NONE, entry(ActionType.OPEN_SETTLEMENT, PartySide.TENANT));

    assertThat(next).isEqualTo(SettlementState.OPENED);
  }

  @Test
  void 이미_열린_정산을_다시_열면_SETTLEMENT_EXISTS() {
    assertThatThrownBy(() ->
            rules.apply(
                SettlementState.OPENED, entry(ActionType.OPEN_SETTLEMENT, PartySide.LANDLORD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTLEMENT_EXISTS));
  }

  @Test
  void 정산이_아닌_행동은_INVALID_STATE() {
    assertThatThrownBy(
            () -> rules.apply(SettlementState.OPENED, entry(ActionType.REGISTER, PartySide.TENANT)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
  }

  @Test
  void 정산_상태를_바꾸는_행동은_정산_열기다() {
    assertThat(SettlementTransitions.CHANGES).contains(ActionType.OPEN_SETTLEMENT);
  }

  private static ActionEntry entry(ActionType type, PartySide side) {
    return new ActionEntry(
        null,
        UUID.randomUUID(),
        0,
        type,
        side,
        "k",
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        NOW);
  }
}
