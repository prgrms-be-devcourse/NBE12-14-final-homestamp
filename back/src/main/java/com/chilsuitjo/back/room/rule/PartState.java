package com.chilsuitjo.back.room.rule;

/**
 * {@link PartTransitions}가 다루는 호실 부위 상태(03 H "부위").
 *
 * @param exists 부위가 있는지. 더하기 전이면 거짓
 * @param toggleable 켜고 끌 수 있는 부위인지(기본 목록의 설비·가전, {@code part_template.optional})
 * @param enabled 켬 여부(이 집에 있다/없다). 숨김과 다르다
 * @param hidden 숨겼는지(목록에서 빠지고 새 기록을 받지 않음)
 */
public record PartState(boolean exists, boolean toggleable, boolean enabled, boolean hidden) {

  /** 아직 더하지 않은 부위. */
  public static final PartState NONE = new PartState(false, false, false, false);

  /** 직접 더한 부위: 켬, 보임, 켜고 끌 수 없음. */
  public static final PartState ADDED = new PartState(true, false, true, false);

  /** 호실을 만들 때 기본 목록에서 복사한 부위: 켬, 보임(행동 없이 생긴다). */
  public static PartState copied(boolean toggleable) {
    return new PartState(true, toggleable, true, false);
  }

  public PartState withHidden(boolean newHidden) {
    return new PartState(exists, toggleable, enabled, newHidden);
  }

  public PartState withEnabled(boolean newEnabled) {
    return new PartState(exists, toggleable, newEnabled, hidden);
  }
}
