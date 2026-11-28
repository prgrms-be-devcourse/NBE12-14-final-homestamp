package com.chilsuitjo.back.settlement.rule;

/**
 * 정산 하나의 상태 값({@link SettlementTransitions}가 다룬다).
 *
 * @param opened 정산이 열렸는지. 아직 없는 정산이면 거짓({@link #NONE})
 */
public record SettlementState(boolean opened) {

  /** 아직 열리지 않은 정산. 재계산의 시작점이다. */
  public static final SettlementState NONE = new SettlementState(false);

  /** 열린 정산. */
  public static final SettlementState OPENED = new SettlementState(true);
}
