package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 수리 건 하나의 상태 값({@link RepairTransitions}가 다룬다). 하자 인정({@code defectAck})과 수리 진행({@code status})은
 * 따로 간다(03 D). 아직 없는 기록이면 모든 칸이 비어 있다({@link #NONE}).
 *
 * <p><b>요청할 때만 아는 값</b>(마지막 세 칸): 호실 단계·공유 중단처럼 행동 기록 밖의 사실이라 {@code load}가 그때 읽어 채운다. 행동 기록만으로 다시
 * 계산할 때({@code Replay.fold}, REP-10)는 알 수 없어 {@code null}이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가 이미 확인했다).
 *
 * @param status 수리 진행(시작 전 → 처리 중 → 종료 확인 대기 → 처리 완료)
 * @param defectAck 하자 인정(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음)
 * @param moneyStatus 수리비 부담(미정에서 시작)
 * @param registeredBy 등록한 측. 하자에 답하는 쪽은 그 반대 측이고, 보충하는 쪽은 이 측이다(REP-2)
 * @param contentNo 지금 판 번호({@code repair_content.no}). 응답할 때 본 판 번호와 맞댄다. 아직 없는 기록이면 null
 * @param lastRerequestAt 마지막으로 보충한 시각(행동 기록 {@code SUPPLEMENT}가 원본). 보충한 적이 없으면 null
 * @param closedBy 누가 확인해 끝났나({@code repair_case.closed_by}). 처리 완료가 아니면 null
 * @param closeRequestedBy 지금 종료를 요청한 측(행동 기록 {@code REQUEST_CLOSE}가 원본). 종료 확인 대기가 아니면 null. 종료
 *     확인·아직 안 됐어요는 그 반대 측이 한다(06 REP-5)
 * @param canOpenDefectConfirm 지금 호실 단계에서 하자 확인 요청을 열 수 있는지(03 K, {@code RequestOpening}). 거주 종료 뒤에는
 *     거짓이라 보충이 막힌다. 재계산이면 null
 * @param canOpenCloseConfirm 지금 호실 단계에서 종료 확인 요청을 열 수 있는지. 거주 종료 뒤에는 거짓이라 종료 요청이 막힌다(decisions
 *     2026-11-19 "거주 종료 뒤 수리 종료 확인은 열 수 없다"). 재계산이면 null
 * @param hiddenFromCounterpart 상대 측이 이 기록을 볼 수 없는지. 어느 쪽이든 공유를 그만둔 뒤에 등록한 기록이면 참이다(03 불변식 18 "그만둔 뒤
 *     남긴 기록은 상대에게 보이지 않는다(양방향)"). 볼 수 없는 기록에는 상대가 행동할 수 없다(안내서 8-3). 재계산이면 null
 */
public record RepairState(
    RepairStatus status,
    AckState defectAck,
    MoneyStatus moneyStatus,
    PartySide registeredBy,
    Integer contentNo,
    Instant lastRerequestAt,
    ClosedBy closedBy,
    PartySide closeRequestedBy,
    Boolean canOpenDefectConfirm,
    Boolean canOpenCloseConfirm,
    Boolean hiddenFromCounterpart) {

  /** 아직 없는 기록. 하자 등록의 지금 상태이고 재계산의 시작점이다(요청할 때만 아는 값은 모름). */
  public static final RepairState NONE =
      new RepairState(null, null, null, null, null, null, null, null, null, null, null);

  /** 하자 확인 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean defectConfirmBlocked() {
    return Boolean.FALSE.equals(canOpenDefectConfirm);
  }

  /** 종료 확인 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean closeConfirmBlocked() {
    return Boolean.FALSE.equals(canOpenCloseConfirm);
  }

  /** 상대에게 보이지 않는다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean hiddenFromCounterpartKnown() {
    return Boolean.TRUE.equals(hiddenFromCounterpart);
  }

  public boolean exists() {
    return status != null;
  }

  /** 하자를 {@code AnswerSupport}가 보는 모양(안내서 8-1). */
  public Answerable defect() {
    return new Answerable(registeredBy, defectAck, contentNo, lastRerequestAt);
  }

  public RepairState withDefectAck(AckState next) {
    return new RepairState(
        status,
        next,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closedBy,
        closeRequestedBy,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        hiddenFromCounterpart);
  }

  public RepairState withLastRerequestAt(Instant at) {
    return new RepairState(
        status,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        at,
        closedBy,
        closeRequestedBy,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        hiddenFromCounterpart);
  }

  /** 수리 진행을 바꾼다(REP-4·5). 종료 요청한 측·끝낸 측도 함께 정한다. */
  public RepairState withProgress(RepairStatus next, PartySide requestedBy, ClosedBy closed) {
    return new RepairState(
        next,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closed,
        requestedBy,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        hiddenFromCounterpart);
  }
}
