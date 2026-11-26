package com.chilsuitjo.back.correction.rule;

import com.chilsuitjo.back.global.common.TargetKind;
import java.util.UUID;

/**
 * 고칠 수 있는 기록 종류(06 4-6 {@code POST /api/corrections} 대상). {@code MOVE_IN_DATE}면 targetId는 호실 기록 ID.
 */
public enum CorrectionTargetKind {
  REPAIR_CASE(TargetKind.REPAIR_CASE),
  PAYMENT(TargetKind.PAYMENT),
  INSPECTION_ITEM(TargetKind.INSPECTION_ITEM),
  SETTLEMENT_TRANSFER(TargetKind.SETTLEMENT_TRANSFER),
  MOVE_IN_DATE(TargetKind.ROOM_RECORD);

  private final TargetKind targetKind;

  CorrectionTargetKind(TargetKind targetKind) {
    this.targetKind = targetKind;
  }

  /**
   * 행동 기록·응답 요청의 대상 종류. 도장 전 고치기({@code CORRECT})와 고친 내용 확인 요청({@code CORRECTION_CONFIRM}, 기록당 하나,
   * 05 2-4)의 대상이 이 기록이다.
   */
  public TargetKind targetKind() {
    return targetKind;
  }

  /** 칸마다 기다리는 고친 내용 하나 제약({@code ux_correction_waiting})에 쓰는 대상 글자. */
  public String targetKey(UUID targetId) {
    return name() + ":" + targetId;
  }
}
