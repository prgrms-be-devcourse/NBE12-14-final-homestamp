package com.chilsuitjo.back.link.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkViewTransitionsTest {

  @Test
  void 링크_열람은_바꿀_상태가_없어_처음도_열람_뒤도_빈_상태다() {
    LinkViewTransitions transitions = LinkViewTransitions.INSTANCE;

    assertThat(transitions.initial()).isNull();
    assertThat(transitions.apply(transitions.initial(), view(PartySide.LANDLORD))).isNull();
    assertThat(transitions.apply(null, view(PartySide.TENANT))).isNull();
  }

  private static ActionEntry view(PartySide side) {
    return new ActionEntry(
        null,
        null,
        0,
        ActionType.LINK_VIEW,
        side,
        "link:l1",
        "k1",
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        Instant.parse("2026-11-28T00:00:00Z"));
  }
}
