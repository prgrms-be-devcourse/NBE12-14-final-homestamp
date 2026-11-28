package com.chilsuitjo.back.link.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;

/**
 * 링크 열람({@code LINK_VIEW})의 상태 전이. 링크 열람에는 바꿀 상태가 없다(저장은 {@code LinkViewStateStore}가 전달 증거만 적음).
 */
public final class LinkViewTransitions implements Transitions<Void> {

  public static final LinkViewTransitions INSTANCE = new LinkViewTransitions();

  private LinkViewTransitions() {}

  @Override
  public Void initial() {
    return null;
  }

  @Override
  public Void apply(Void now, ActionEntry action) {
    return null;
  }
}
