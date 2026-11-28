package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ActionType.Allow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 호실 상세 {@code availableActions}의 {@code parts}는 {@code ADD_PART} 하나로 네 부위 행동을 대표한다(#456). 네 행동의 호실
 * 단위 허용 값이 어긋나면 버튼과 실제 요청이 조용히 달라지므로, 하나가 바뀌면 여기서 걸리게 한다.
 */
class PartActionGateTest {

  private static final List<ActionType> PART_ACTIONS =
      List.of(
          ActionType.ADD_PART, ActionType.HIDE_PART, ActionType.SHOW_PART, ActionType.TOGGLE_PART);

  @Test
  void 부위_행동_넷은_호실_단위_허용_값이_ADD_PART와_같다() {
    for (ActionType type : PART_ACTIONS) {
      for (Allow allow : Allow.values()) {
        assertThat(type.allows(allow))
            .as("%s %s", type, allow)
            .isEqualTo(ActionType.ADD_PART.allows(allow));
      }
      assertThat(type.blockedAfterEnded())
          .as("%s 거주 종료 뒤", type)
          .isEqualTo(ActionType.ADD_PART.blockedAfterEnded());
    }
  }
}
