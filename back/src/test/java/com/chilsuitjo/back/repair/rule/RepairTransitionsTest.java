package com.chilsuitjo.back.repair.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 수리 건 규칙(03 D, 06 REP-1). 순수 함수라 pipeline 없이 부른다(안내서 5-1). */
class RepairTransitionsTest {

  private final RepairTransitions rules = new RepairTransitions();

  @Test
  void 처음_상태는_아직_없는_기록이다() {
    assertThat(rules.initial()).isEqualTo(RepairState.NONE);
    assertThat(rules.initial().exists()).isFalse();
  }

  @Test
  void 등록하면_시작_전이고_확인을_기다리며_부담은_미정이다() {
    RepairState next =
        rules.apply(RepairState.NONE, entry(ActionType.REGISTER, PartySide.LANDLORD));

    assertThat(next)
        .isEqualTo(
            new RepairState(
                RepairStatus.NOT_STARTED,
                AckState.PENDING,
                MoneyStatus.UNDECIDED,
                PartySide.LANDLORD));
    assertThat(next.exists()).isTrue();
  }

  @Test
  void 이미_있는_기록에는_다시_등록할_수_없다() {
    RepairState registered =
        rules.apply(RepairState.NONE, entry(ActionType.REGISTER, PartySide.TENANT));

    assertError(() -> rules.apply(registered, entry(ActionType.REGISTER, PartySide.TENANT)));
  }

  @Test
  void 측이_없는_주체는_등록할_수_없다() {
    assertError(() -> rules.apply(RepairState.NONE, entry(ActionType.REGISTER, null)));
  }

  /** REGISTER 말고는 아직 만들지 않았다. 새 행동을 붙이면 그 행동은 여기서 빠지고 따로 테스트한다. */
  static Stream<ActionType> notYetRepairActions() {
    return Arrays.stream(ActionType.values())
        .filter(type -> type.area() == ActionType.Area.REPAIR && type != ActionType.REGISTER);
  }

  @ParameterizedTest
  @MethodSource("notYetRepairActions")
  void 아직_만들지_않은_수리_행동은_INVALID_STATE(ActionType type) {
    RepairState registered =
        rules.apply(RepairState.NONE, entry(ActionType.REGISTER, PartySide.TENANT));

    assertError(() -> rules.apply(registered, entry(type, PartySide.LANDLORD)));
  }

  @Test
  void 수리가_아닌_행동은_INVALID_STATE() {
    assertError(() -> rules.apply(RepairState.NONE, entry(ActionType.HIDE_PART, PartySide.TENANT)));
  }

  private static void assertError(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
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
        Instant.parse("2026-11-23T00:00:00Z"));
  }
}
