package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.repair.rule.ClosedBy;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import java.util.UUID;

/**
 * 수리 진행 행동의 서비스 결과. 컨트롤러가 {@link RepairProgressResponse}로 바꾸고 {@code replayed}면 {@code
 * Idempotent-Replayed} 머리를 붙인다(06 4-0).
 */
public record RepairProgressChanged(
    UUID id, RepairStatus status, ClosedBy closedBy, AckState defectAck, boolean replayed) {

  public RepairProgressResponse response() {
    return new RepairProgressResponse(id, status, closedBy, defectAck);
  }
}
