package com.chilsuitjo.back.notification.dto;

import com.chilsuitjo.back.global.common.TargetKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 응답 요청이 가리키는 기록. 종류는 행동 대상과 같은 {@link TargetKind}를 쓴다(decisions 2026-11-17).
 *
 * <p>호실 기록 자체가 대상인 요청(정산 끝내기, 거주 종료 요청)은 {@link #roomRecord(UUID)}로 만든다.
 */
public record RequestTarget(TargetKind kind, UUID id) {

  public RequestTarget {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
  }

  public static RequestTarget of(TargetKind kind, UUID id) {
    return new RequestTarget(kind, id);
  }

  public static RequestTarget roomRecord(UUID roomRecordId) {
    return new RequestTarget(TargetKind.ROOM_RECORD, roomRecordId);
  }
}
