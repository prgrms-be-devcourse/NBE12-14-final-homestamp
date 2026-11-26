package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.util.Objects;
import java.util.UUID;

/**
 * "상대 응답 없이 혼자 진행할 수 있나" 묻기(03 1-4 "혼자 진행 가능"). 요청 ID는 담지 않는다(요청은 대상 + 종류로 찾는다, #86).
 *
 * @param kind 요청 기준({@code canGoSolo})이면 그 요청의 종류, 바로 혼자 진행({@code canGoSoloDirectly})이면 혼자 진행이
 *     대신하는 요청 종류(수리 종료면 {@code CLOSE_CONFIRM})
 * @param target 그 기록(수리 건 등)
 * @param requestedBySide 요청 기준이면 요청을 보낸 측, 바로 혼자 진행이면 행동하는 측. 화면에서 받은 값이 아니라 pipeline이 인증된 주체로 정한
 *     측이다
 */
public record SoloQuery(
    UUID roomRecordId, RequestKind kind, RequestTarget target, PartySide requestedBySide) {

  public SoloQuery {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedBySide, "requestedBySide");
  }
}
