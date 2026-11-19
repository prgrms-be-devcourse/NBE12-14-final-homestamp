package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import java.util.List;

/**
 * 행동 기록을 처음부터 접어 상태를 다시 계산한다. 재계산 테스트(REP-10)와 공유를 그만둔 뒤 "그 시각까지의 상태"(불변식 18)에 쓴다.
 *
 * <p>그래서 상태 칸은 {@code apply} 결과로만 바뀌어야 한다. 미확인 이벤트 같은 리스너도 상태를 직접 바꾸지 않고 자기 {@code Transitions}를
 * 거친다.
 */
public final class Replay {

  private Replay() {}

  public static <S> S fold(Transitions<S> transitions, List<ActionEntry> actions) {
    S state = transitions.initial();
    for (ActionEntry action : actions) {
      state = transitions.apply(state, action);
    }
    return state;
  }
}
