package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;

/**
 * 수리 건 하나의 상태 값({@link RepairTransitions}가 다룬다). 하자 인정({@code defectAck})과 수리 진행({@code status})은
 * 따로 간다(03 D). 아직 없는 기록이면 모든 칸이 null이다({@link #NONE}).
 *
 * @param status 수리 진행(시작 전 → 처리 중 → 종료 확인 대기 → 처리 완료)
 * @param defectAck 하자 인정(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음)
 * @param moneyStatus 수리비 부담(미정에서 시작)
 * @param registeredBy 등록한 측. 하자에 답하는 쪽은 그 반대 측이다(REP-2)
 */
public record RepairState(
    RepairStatus status, AckState defectAck, MoneyStatus moneyStatus, PartySide registeredBy) {

  /** 아직 없는 기록. 하자 등록의 지금 상태이고 재계산의 시작점이다. */
  public static final RepairState NONE = new RepairState(null, null, null, null);

  public boolean exists() {
    return status != null;
  }
}
