package com.chilsuitjo.back.notification.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RenotifyTransitionsTest {

  @Test
  void 처음_상태는_정한_재통지가_없다() {
    assertThat(RenotifyTransitions.INSTANCE.initial()).isNull();
  }

  @Test
  void 재통지_행동은_잠근_뒤_정한_재통지를_바꾸지_않고_그대로_넘긴다() {
    RenotifyPlan plan =
        new RenotifyPlan(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    assertThat(RenotifyTransitions.INSTANCE.apply(plan, renotify())).isSameAs(plan);
  }

  private static ActionEntry renotify() {
    return new ActionEntry(
        null,
        null,
        0,
        ActionType.RENOTIFY,
        null,
        "server",
        "rn:r1:-",
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        Instant.parse("2026-11-28T00:00:00Z"));
  }
}
