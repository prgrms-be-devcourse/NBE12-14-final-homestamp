package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 점검 항목 하나의 상태 값({@link InspectionItemTransitions}가 다룬다). 항목은 그 점검의 상태에 따라 할 수 있는 일이 달라서 점검의 상태·기준
 * 측을 함께 담는다.
 *
 * <p><b>요청할 때만 아는 값</b>({@code canOpenConfirm}·{@code hiddenFromActor})은 재계산({@code Replay.fold})에서
 * null이고, 그때는 처음 요청 때 서버가 확인했다고 본다.
 *
 * @param inspectionStatus 이 항목이 속한 점검의 상태
 * @param inspectionAuthor 그 점검의 기준 측
 * @param status 항목 응답(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음). 아직 없는 항목이면 null
 * @param addedBy 항목을 더한 측. 그 항목에 답하는 쪽은 반대 측이다(03 H). 아직 없는 항목이면 null
 * @param versionNo 지금 판 번호({@code inspection_item_version.no}). 아직 없는 항목이면 null
 * @param lastRerequestAt 마지막 보충 시각(행동 기록이 원본, 안내서 8-1). 없으면 null
 * @param canOpenConfirm 지금 호실 단계에서 점검 확인 요청을 열 수 있나(03 K)
 * @param hiddenFromActor 이 항목이 행동하는 사람에게 보이지 않나(06 INSP-1a 작성 중, 불변식 18)
 */
public record InspectionItemState(
    InspectionStatus inspectionStatus,
    PartySide inspectionAuthor,
    AckState status,
    PartySide addedBy,
    Integer versionNo,
    Instant lastRerequestAt,
    Boolean canOpenConfirm,
    Boolean hiddenFromActor) {

  /** 재계산의 시작점(점검 상태는 모름). */
  public static final InspectionItemState NONE =
      new InspectionItemState(null, null, null, null, null, null, null, null);

  /** 이 점검에 아직 없는 항목의 지금 상태. */
  public static InspectionItemState newIn(InspectionStatus inspectionStatus, PartySide author) {
    return new InspectionItemState(inspectionStatus, author, null, null, null, null, null, null);
  }

  public boolean exists() {
    return status != null;
  }

  /** 응답·보충 공통 규칙({@code AnswerSupport})에 넘기는 값. 기록한 측은 항목을 더한 측이다. */
  public Answerable answerable() {
    return new Answerable(addedBy, status, versionNo, lastRerequestAt);
  }

  public InspectionItemState withStatus(AckState next) {
    return new InspectionItemState(
        inspectionStatus,
        inspectionAuthor,
        next,
        addedBy,
        versionNo,
        lastRerequestAt,
        canOpenConfirm,
        hiddenFromActor);
  }

  public InspectionItemState withLastRerequestAt(Instant at) {
    return new InspectionItemState(
        inspectionStatus,
        inspectionAuthor,
        status,
        addedBy,
        versionNo,
        at,
        canOpenConfirm,
        hiddenFromActor);
  }
}
