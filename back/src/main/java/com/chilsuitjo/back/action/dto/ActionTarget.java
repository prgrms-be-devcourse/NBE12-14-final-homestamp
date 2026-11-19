package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.global.common.TargetKind;
import java.util.Objects;
import java.util.UUID;

/**
 * 무엇에 대고 눌렀나: 05 {@code action}의 대상 칸 하나(종류 + ID). 호실 기록 자체가 대상인 행동은 {@code null}로 두고, 결과 ({@link
 * ActionResult#target()})에서만 {@link TargetKind#ROOM_RECORD}로 돌려준다.
 *
 * <p>다른 호실의 대상을 가리키면 행동 기록의 복합 FK({@code (대상 ID, room_record_id)}, 05 0장)가 막는다.
 */
public record ActionTarget(TargetKind kind, UUID id) {

  public ActionTarget {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
  }

  public static ActionTarget of(TargetKind kind, UUID id) {
    return new ActionTarget(kind, id);
  }

  public static ActionTarget roomRecord(UUID id) {
    return of(TargetKind.ROOM_RECORD, id);
  }

  public static ActionTarget party(UUID id) {
    return of(TargetKind.PARTY, id);
  }

  public static ActionTarget repairCase(UUID id) {
    return of(TargetKind.REPAIR_CASE, id);
  }

  public static ActionTarget payment(UUID id) {
    return of(TargetKind.PAYMENT, id);
  }

  public static ActionTarget inspection(UUID id) {
    return of(TargetKind.INSPECTION, id);
  }

  public static ActionTarget settlement(UUID id) {
    return of(TargetKind.SETTLEMENT, id);
  }

  public static ActionTarget correctionRequest(UUID id) {
    return of(TargetKind.CORRECTION_REQUEST, id);
  }

  public static ActionTarget photo(UUID id) {
    return of(TargetKind.PHOTO, id);
  }
}
