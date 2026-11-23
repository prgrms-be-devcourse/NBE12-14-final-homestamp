package com.chilsuitjo.back.notification.entity;

import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import java.util.UUID;

/**
 * {@link RequestTarget}을 V1 {@code state.response_request}의 대상 칸 여덟 개로 바꾼 값. 엔티티 저장과 Repository 조회가
 * 같은 변환을 쓰도록 한 곳에 둔다(#117). 칸은 많아야 하나만 채우고(V1 {@code num_nonnulls <= 1}), 모두 비어 있으면 호실 기록 자체가 대상이다.
 *
 * @param roomRecordTarget 호실 기록 자체가 대상이면 true(칸은 모두 null)
 */
public record RequestTargetColumns(
    UUID repairCaseId,
    UUID paymentId,
    UUID settlementItemId,
    UUID settlementId,
    UUID inspectionId,
    UUID inspectionItemId,
    UUID settlementTransferId,
    UUID progressNoteId,
    boolean roomRecordTarget) {

  /**
   * V1에 칸이 없는 종류(링크·제안·사진 등)는 저장도 조회도 할 수 없으므로 거절한다.
   *
   * <p>{@code case null}이 있어야 컴파일러가 모든 {@link TargetKind}를 다뤘는지 검사한다. enum에 화살표만 쓴 switch 문은 빠진 값이
   * 있어도 컴파일되고, 그 값은 아무 칸도 채우지 않아 호실 기록 대상처럼 다뤄진다. {@code RequestTarget}이 종류를 null로 두지 않으니 이 줄은 실제로
   * 돌지 않는다.
   */
  public static RequestTargetColumns of(RequestTarget target) {
    UUID id = target.id();
    return switch (target.kind()) {
      case null -> throw new NullPointerException("target.kind");
      case ROOM_RECORD ->
          new RequestTargetColumns(null, null, null, null, null, null, null, null, true);
      case REPAIR_CASE ->
          new RequestTargetColumns(id, null, null, null, null, null, null, null, false);
      case PAYMENT -> new RequestTargetColumns(null, id, null, null, null, null, null, null, false);
      case SETTLEMENT_ITEM ->
          new RequestTargetColumns(null, null, id, null, null, null, null, null, false);
      case SETTLEMENT ->
          new RequestTargetColumns(null, null, null, id, null, null, null, null, false);
      case INSPECTION ->
          new RequestTargetColumns(null, null, null, null, id, null, null, null, false);
      case INSPECTION_ITEM ->
          new RequestTargetColumns(null, null, null, null, null, id, null, null, false);
      case SETTLEMENT_TRANSFER ->
          new RequestTargetColumns(null, null, null, null, null, null, id, null, false);
      case PROGRESS_NOTE ->
          new RequestTargetColumns(null, null, null, null, null, null, null, id, false);
      case PARTY,
          LINK,
          ROOM_PART,
          NOTICE,
          PROPOSAL,
          RESPONSE_REQUEST,
          CORRECTION_REQUEST,
          CAUSE_NOTE,
          PHOTO ->
          throw new IllegalArgumentException("응답 요청은 이 대상을 가리킬 수 없어요: " + target.kind());
    };
  }
}
