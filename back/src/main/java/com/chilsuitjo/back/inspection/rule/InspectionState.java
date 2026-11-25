package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.global.common.PartySide;

/**
 * 점검 하나의 상태 값({@link InspectionTransitions}가 다룬다). 아직 없는 점검이면 모든 칸이 비어 있다({@link #NONE}).
 *
 * @param kind 입주·퇴거
 * @param status 작성 중 → 확인 요청 → 잠김
 * @param authorSide 기준 기록을 쓰는 측(먼저 시작한 측, 03 H)
 *     <p><b>요청할 때만 아는 값</b>(마지막 두 칸): 호실 기록의 사실이라 {@code load}가 그때 읽어 채운다. 행동 기록만으로 다시 계산할 때({@code
 *     Replay.fold})는 알 수 없어 {@code null}이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가 이미 확인했다).
 * @param moveInDateMissing 호실의 입주일이 비어 있는지(06 INSP-2b). 재계산이면 null
 * @param roomEnded 호실이 거주 종료 뒤인지(정산 완료 포함). 재계산이면 null
 */
public record InspectionState(
    InspectionKind kind,
    InspectionStatus status,
    PartySide authorSide,
    Boolean moveInDateMissing,
    Boolean roomEnded) {

  /** 아직 없는 점검. 재계산의 시작점이다(요청할 때만 아는 값은 모름). */
  public static final InspectionState NONE = new InspectionState(null, null, null, null, null);

  /** 아직 없는 점검의 지금 상태. 시작하기 전에 호실 사실만 담는다. */
  public static InspectionState notStarted(boolean moveInDateMissing, boolean roomEnded) {
    return new InspectionState(null, null, null, moveInDateMissing, roomEnded);
  }

  public boolean exists() {
    return status != null;
  }
}
