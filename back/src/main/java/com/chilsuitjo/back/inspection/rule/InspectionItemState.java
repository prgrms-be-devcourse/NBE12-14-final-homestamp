package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;

/**
 * 점검 항목 하나의 상태 값({@link InspectionItemTransitions}가 다룬다). 항목은 그 점검의 상태에 따라 할 수 있는 일이 달라서 점검의 상태·기준
 * 측을 함께 담는다.
 *
 * @param inspectionStatus 이 항목이 속한 점검의 상태
 * @param inspectionAuthor 그 점검의 기준 측
 * @param status 항목 응답(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음). 아직 없는 항목이면 null
 * @param addedBy 항목을 더한 측. 그 항목에 답하는 쪽은 반대 측이다(03 H). 아직 없는 항목이면 null
 * @param versionNo 지금 판 번호({@code inspection_item_version.no}). 아직 없는 항목이면 null
 */
public record InspectionItemState(
    InspectionStatus inspectionStatus,
    PartySide inspectionAuthor,
    AckState status,
    PartySide addedBy,
    Integer versionNo) {

  /** 재계산의 시작점(점검 상태는 모름). */
  public static final InspectionItemState NONE =
      new InspectionItemState(null, null, null, null, null);

  /** 이 점검에 아직 없는 항목의 지금 상태. */
  public static InspectionItemState newIn(InspectionStatus inspectionStatus, PartySide author) {
    return new InspectionItemState(inspectionStatus, author, null, null, null);
  }

  public boolean exists() {
    return status != null;
  }
}
