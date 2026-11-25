package com.chilsuitjo.back.room.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 03 H "부위"·06 ROOM-3 부위 상태 전이(#178). */
class PartTransitionsTest {

  private final PartTransitions rules = new PartTransitions();

  private static final PartState VISIBLE = new PartState(true, false, true, false);
  private static final PartState APPLIANCE_ON = new PartState(true, true, true, false);

  @Test
  void 더한_부위는_켬_보임이고_켜고_끌_수_없다() {
    PartState next = rules.apply(rules.initial(), entry(ActionType.ADD_PART, Map.of()));

    assertThat(next).isEqualTo(new PartState(true, false, true, false));
  }

  @Test
  void 이미_있는_부위를_다시_더할_수_없다() {
    expect(ErrorCode.INVALID_STATE, VISIBLE, ActionType.ADD_PART, Map.of());
  }

  @Test
  void 숨기면_숨김이고_켬_여부는_그대로다() {
    PartState next = rules.apply(APPLIANCE_ON, entry(ActionType.HIDE_PART, Map.of()));

    assertThat(next).isEqualTo(new PartState(true, true, true, true));
  }

  @Test
  void 숨긴_부위를_또_숨기면_INVALID_STATE() {
    expect(ErrorCode.INVALID_STATE, VISIBLE.withHidden(true), ActionType.HIDE_PART, Map.of());
  }

  @Test
  void 숨긴_부위는_다시_보이게_할_수_있다() {
    PartState next = rules.apply(VISIBLE.withHidden(true), entry(ActionType.SHOW_PART, Map.of()));

    assertThat(next).isEqualTo(VISIBLE);
  }

  @Test
  void 보이는_부위를_다시_보이게_하면_INVALID_STATE() {
    expect(ErrorCode.INVALID_STATE, VISIBLE, ActionType.SHOW_PART, Map.of());
  }

  @Test
  void 설비_가전은_원하는_값으로_켜고_끈다_숨김은_그대로다() {
    PartState off =
        rules.apply(
            APPLIANCE_ON.withHidden(true), entry(ActionType.TOGGLE_PART, Map.of("enabled", false)));
    assertThat(off).isEqualTo(new PartState(true, true, false, true));

    PartState on = rules.apply(off, entry(ActionType.TOGGLE_PART, Map.of("enabled", true)));
    assertThat(on).isEqualTo(new PartState(true, true, true, true));
  }

  @Test
  void 이미_그_값이면_INVALID_STATE() {
    expect(ErrorCode.INVALID_STATE, APPLIANCE_ON, ActionType.TOGGLE_PART, Map.of("enabled", true));
  }

  @Test
  void 설비_가전이_아니거나_값이_없으면_VALIDATION_FAILED() {
    expect(ErrorCode.VALIDATION_FAILED, VISIBLE, ActionType.TOGGLE_PART, Map.of("enabled", false));
    expect(ErrorCode.VALIDATION_FAILED, APPLIANCE_ON, ActionType.TOGGLE_PART, Map.of());
  }

  @Test
  void 없는_부위는_숨기거나_보이거나_켜고_끌_수_없다() {
    expect(ErrorCode.INVALID_STATE, PartState.NONE, ActionType.HIDE_PART, Map.of());
    expect(ErrorCode.INVALID_STATE, PartState.NONE, ActionType.SHOW_PART, Map.of());
    expect(
        ErrorCode.INVALID_STATE, PartState.NONE, ActionType.TOGGLE_PART, Map.of("enabled", true));
  }

  @Test
  void 부위_행동이_아니면_거절한다() {
    expect(ErrorCode.INVALID_STATE, VISIBLE, ActionType.CREATE_ROOM, Map.of());
  }

  private void expect(ErrorCode code, PartState now, ActionType type, Map<String, Object> payload) {
    assertThatThrownBy(() -> rules.apply(now, entry(type, payload)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  private static ActionEntry entry(ActionType type, Map<String, Object> payload) {
    return new ActionEntry(
        null,
        null,
        0,
        type,
        PartySide.TENANT,
        "member",
        "k1",
        null,
        null,
        null,
        null,
        null,
        new HashMap<>(payload),
        Instant.parse("2026-11-24T00:00:00Z"));
  }
}
