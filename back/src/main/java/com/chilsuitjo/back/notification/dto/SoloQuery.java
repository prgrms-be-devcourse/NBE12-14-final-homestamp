package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.RequestKind;
import java.util.Objects;
import java.util.UUID;

/** "상대 응답 없이 혼자 진행할 수 있나" 묻기(03 1-4 "혼자 진행 가능"). 묻는 쪽은 요청을 보낸 측이다. */
public record SoloQuery(
    UUID roomRecordId, RequestKind kind, RequestTarget target, PartySide requestedBySide) {

  public SoloQuery {
    Objects.requireNonNull(roomRecordId, "roomRecordId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedBySide, "requestedBySide");
  }
}
