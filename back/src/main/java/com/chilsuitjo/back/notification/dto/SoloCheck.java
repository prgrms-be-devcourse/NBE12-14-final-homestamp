package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.notification.rule.SoloReason;
import java.time.Instant;

/**
 * 혼자 진행 판단 결과.
 *
 * @param allowed 지금 혼자 진행할 수 있나
 * @param reason 할 수 있으면 그 이유(화면에 보여 줌, 03 1-4 ①~⑥), 아니면 null
 * @param opensAt 아직 못 하지만 기다리면 열리는 시각(미확인 기한). 모르거나 열리지 않으면 null
 */
public record SoloCheck(boolean allowed, SoloReason reason, Instant opensAt) {

  public static SoloCheck allowedBecause(SoloReason reason) {
    return new SoloCheck(true, reason, null);
  }

  public static SoloCheck notYet(Instant opensAt) {
    return new SoloCheck(false, null, opensAt);
  }
}
