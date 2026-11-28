package com.chilsuitjo.back.notification.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;

/**
 * 재통지 행동({@code RENOTIFY})의 전이. 요청 행은 바꾸지 않고(저장한 기한은 그대로, 06 0-1) 잠근 뒤 정한 재통지를 그대로 넘긴다. 할 수 있는지는
 * {@code RenotifyStateStore.check}가 잠근 뒤 본다(서버 행동이라 사용자 규칙 오류가 없다).
 */
public enum RenotifyTransitions implements Transitions<RenotifyPlan> {
  INSTANCE;

  @Override
  public RenotifyPlan initial() {
    return null;
  }

  @Override
  public RenotifyPlan apply(RenotifyPlan now, ActionEntry action) {
    return now;
  }
}
