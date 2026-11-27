package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.Objects;
import java.util.UUID;

/**
 * 확인 요청 상태인 점검 하나({@code InspectionsAwaitingConfirm}). 답할 측은 {@code baseSide}의 상대다.
 *
 * @param inspectionId 점검
 * @param baseSide 점검을 기록하고 확인을 요청한 측(응답 요청의 {@code requested_by_side})
 */
public record AwaitingConfirm(UUID inspectionId, PartySide baseSide) {
  public AwaitingConfirm {
    Objects.requireNonNull(inspectionId, "inspectionId");
    Objects.requireNonNull(baseSide, "baseSide");
  }
}
