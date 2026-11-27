package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 점검 하나의 상태 값({@link InspectionTransitions}가 다룬다). 아직 없는 점검이면 모든 칸이 비어 있다({@link #NONE}).
 *
 * @param kind 입주·퇴거
 * @param status 작성 중 → 확인 요청 → 잠김
 * @param authorSide 기준 기록을 쓰는 측(먼저 시작한 측, 03 H)
 * @param startedAt 점검을 시작한 시각(시작 행동의 시각). 작성 중 기간(`inspection.draft.stale-after`)을 이 시각부터 센다
 *     <p><b>요청할 때만 아는 값</b>(마지막 세 칸): 호실 기록·응답 요청의 사실이라 {@code load}가 그때 읽어 채운다. 행동 기록만으로 다시 계산할
 *     때({@code Replay.fold})는 알 수 없어 {@code null}이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가 이미 확인했다). 작성 중 기간
 *     검사도 같다({@link #atRequest}).
 * @param moveInDateMissing 호실의 입주일이 비어 있는지(06 INSP-2b). 재계산이면 null
 * @param roomEnded 호실이 거주 종료 뒤인지(정산 완료 포함). 재계산이면 null
 * @param canOpenConfirm 지금 호실 단계에서 점검 확인 요청을 열 수 있는지(03 K, {@code RequestOpening}). 재계산이면 null
 * @param moveInDate 호실에 이미 있는 입주일(시작하기 전 호실 사실). 비었거나 재계산이면 null. 시작이 채우는 입주일은 행동 payload에 있다
 * @param late 입주일보다 늦게 시작한 입주 점검인가(06 INSP-2a, {@code inspection.late}). 시작이 정하고 그 뒤로 바뀌지 않는다. 퇴거
 *     점검은 늘 거짓이다. 재계산에서 입주일을 모르면 null
 */
public record InspectionState(
    InspectionKind kind,
    InspectionStatus status,
    PartySide authorSide,
    Instant startedAt,
    Boolean moveInDateMissing,
    Boolean roomEnded,
    Boolean canOpenConfirm,
    LocalDate moveInDate,
    Boolean late) {

  /** 아직 없는 점검. 재계산의 시작점이다(요청할 때만 아는 값은 모름). */
  public static final InspectionState NONE =
      new InspectionState(null, null, null, null, null, null, null, null, null);

  /** 아직 없는 점검의 지금 상태. 시작하기 전에 호실 사실만 담는다. 입주일 값은 모른다(늦었는지는 정하지 않음). */
  public static InspectionState notStarted(boolean moveInDateMissing, boolean roomEnded) {
    return new InspectionState(
        null, null, null, null, moveInDateMissing, roomEnded, null, null, null);
  }

  /**
   * 아직 없는 점검의 지금 상태({@code InspectionStateStore.load}). 입주일이 null이면 호실 입주일이 비어 있는 것이다(06 INSP-2b).
   *
   * @param moveInDate 호실의 지금 입주일
   */
  public static InspectionState notStarted(LocalDate moveInDate, boolean roomEnded) {
    return new InspectionState(
        null, null, null, null, moveInDate == null, roomEnded, null, moveInDate, null);
  }

  public boolean exists() {
    return status != null;
  }

  /** 점검 확인 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean confirmBlocked() {
    return Boolean.FALSE.equals(canOpenConfirm);
  }

  /**
   * 있는 점검을 요청할 때 읽은 상태인가({@code InspectionStateStore.current}가 {@code canOpenConfirm}을 늘 채운다).
   * 재계산({@code Replay.fold})이면 거짓이고, 그때는 요청할 때만 보는 검사를 하지 않는다. 지금 설정값(작성 중 기간 {@code
   * inspection.draft.stale-after})에 기대는 검사도 여기에 든다: 기록된 행동은 그때 설정으로 서버가 이미 확인했고, 설정을 바꾼 뒤 다시 계산해도
   * 같은 상태가 나와야 한다(#234 리뷰).
   */
  public boolean atRequest() {
    return canOpenConfirm != null;
  }

  public InspectionState withStatus(InspectionStatus next) {
    return new InspectionState(
        kind,
        next,
        authorSide,
        startedAt,
        moveInDateMissing,
        roomEnded,
        canOpenConfirm,
        moveInDate,
        late);
  }
}
