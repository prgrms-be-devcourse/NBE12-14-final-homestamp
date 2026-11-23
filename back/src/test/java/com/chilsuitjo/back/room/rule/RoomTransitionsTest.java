package com.chilsuitjo.back.room.rule;

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

/** 03 A 호실 기록 상태 전이 중 이번에 만든 것: 호실 기록 만들기. */
class RoomTransitionsTest {

  private final RoomTransitions rules = new RoomTransitions();

  @Test
  void 호실_기록을_만들면_거주_중이다() {
    RoomState next = rules.apply(rules.initial(), entry(ActionType.CREATE_ROOM));

    assertThat(next.status()).isEqualTo(RoomStatus.LIVING);
  }

  @Test
  void 이미_있는_호실을_다시_만들_수_없다() {
    RoomState living = new RoomState(RoomStatus.LIVING);

    assertThatThrownBy(() -> rules.apply(living, entry(ActionType.CREATE_ROOM)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
  }

  @Test
  void 아직_받지_않는_행동은_거절한다() {
    assertThatThrownBy(
            () -> rules.apply(new RoomState(RoomStatus.LIVING), entry(ActionType.EDIT_ROOM_INFO)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE));
  }

  private static ActionEntry entry(ActionType type) {
    UUID memberId = UUID.randomUUID();
    return new ActionEntry(
        null,
        null,
        0,
        type,
        PartySide.TENANT,
        memberId.toString(),
        "k1",
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        Instant.parse("2026-11-23T00:00:00Z"));
  }
}
