package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 공유를 그만둔 뒤 보는 사람에게 보이는 부위 상태(03 6-6 9번 "그만둔 시각 기준으로 고정, 상태도 그 시각까지로 다시 계산", 3-1, 불변식 18, #180 리뷰).
 * 수리 건 상세({@code RepairQueryService.visibleState})와 같은 기준이다: 고정 시각까지 남은 행동(그 시각 포함)과 보는 사람 측의 행동만
 * 남겨 같은 규칙({@link PartTransitions})으로 다시 계산한다.
 *
 * <p>기본 부위는 호실을 만들 때 행동 없이 복사되므로 {@link PartState#copied}에서, 직접 더한 부위는 {@link PartState#NONE}에서
 * 시작한다. 다시 계산한 결과 {@code exists}가 거짓이면(고정 시각 뒤 상대가 더한 부위) 보는 사람에게 없는 부위다.
 */
public final class PartVisibility {

  private PartVisibility() {}

  /** 이 행동이 보는 사람에게 보이나. */
  public static boolean visible(ActionEntry action, PartySide viewer, Instant asOf) {
    return !action.createdAt().isAfter(asOf) || action.actorSide() == viewer;
  }

  /**
   * @param start 행동 전 상태(기본 부위면 {@link PartState#copied}, 직접 더한 부위면 {@link PartState#NONE})
   * @param actions 그 부위를 대상으로 남은 부위 행동, {@code room_seq} 순
   */
  public static PartState asSeenBy(
      Transitions<PartState> rules,
      PartState start,
      List<ActionEntry> actions,
      PartySide viewer,
      Instant asOf) {
    Objects.requireNonNull(asOf, "asOf");
    PartState state = start;
    for (ActionEntry action : actions) {
      if (visible(action, viewer, asOf)) {
        state = rules.apply(state, action);
      }
    }
    return state;
  }
}
