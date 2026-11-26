package com.chilsuitjo.back.room.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 공유를 그만둔 뒤 보이는 부위 상태(03 6-6 9번, 3-1, 불변식 18, #180 리뷰). */
class PartVisibilityTest {

  private final PartTransitions rules = new PartTransitions();

  private static final Instant STOPPED = Instant.parse("2026-11-24T09:00:00Z");
  private static final Instant BEFORE = STOPPED.minusSeconds(60);
  private static final Instant AFTER = STOPPED.plusSeconds(60);

  @Test
  void 그만둔_시각_뒤_상대가_더한_부위는_없는_부위다() {
    PartState seen =
        seenByTenant(
            PartState.NONE, List.of(entry(ActionType.ADD_PART, PartySide.LANDLORD, AFTER)));

    assertThat(seen.exists()).isFalse();
  }

  @Test
  void 그만둔_시각_뒤_상대가_바꾼_숨김과_켜고_끔은_드러나지_않는다() {
    PartState seen =
        seenByTenant(
            PartState.copied(true),
            List.of(
                entry(ActionType.HIDE_PART, PartySide.LANDLORD, AFTER),
                toggle(PartySide.LANDLORD, AFTER, false)));

    assertThat(seen).isEqualTo(PartState.copied(true));
  }

  @Test
  void 그만둔_시각까지의_행동은_그_시각에_남은_것까지_보인다() {
    PartState seen =
        seenByTenant(
            PartState.copied(true),
            List.of(
                entry(ActionType.HIDE_PART, PartySide.LANDLORD, BEFORE),
                toggle(PartySide.LANDLORD, STOPPED, false),
                entry(ActionType.SHOW_PART, PartySide.LANDLORD, AFTER)));

    assertThat(seen).isEqualTo(new PartState(true, true, false, true));
  }

  @Test
  void 보는_사람_자신의_행동은_그_시각_뒤라도_보인다() {
    PartState seen =
        PartVisibility.asSeenBy(
            rules,
            PartState.NONE,
            List.of(
                entry(ActionType.ADD_PART, PartySide.LANDLORD, AFTER),
                entry(ActionType.HIDE_PART, PartySide.LANDLORD, AFTER)),
            PartySide.LANDLORD,
            STOPPED);

    assertThat(seen).isEqualTo(PartState.ADDED.withHidden(true));
  }

  private PartState seenByTenant(PartState start, List<ActionEntry> actions) {
    return PartVisibility.asSeenBy(rules, start, actions, PartySide.TENANT, STOPPED);
  }

  private static ActionEntry toggle(PartySide side, Instant at, boolean enabled) {
    return new ActionEntry(
        null,
        null,
        0,
        ActionType.TOGGLE_PART,
        side,
        "member",
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of("enabled", enabled),
        at);
  }

  private static ActionEntry entry(ActionType type, PartySide side, Instant at) {
    return new ActionEntry(
        null, null, 0, type, side, "member", null, null, null, null, null, null, Map.of(), at);
  }
}
