package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;
import java.util.Objects;

/**
 * 수리 건 하나의 상태 값({@link RepairTransitions}가 다룬다). 하자 인정({@code defectAck})과 수리 진행({@code status})은
 * 따로 간다(03 D). 아직 없는 기록이면 모든 칸이 비어 있다({@link #NONE}).
 *
 * <p><b>요청할 때만 아는 값</b>({@code canOpen…}·{@code hiddenFromCounterpart}·{@code
 * situationCheck.opening}): 호실 단계·공유 중단처럼 행동 기록 밖의 사실이라 {@code load}가 그때 읽어 채운다. 행동 기록만으로 다시 계산할
 * 때({@code Replay.fold}, REP-10)는 알 수 없어 {@code null}이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가 이미 확인했다).
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
 * @param lastProgressAskAt 마지막으로 진행 상황을 물은 시각(행동 기록 {@code ASK_PROGRESS}가 원본, 누가 물었든). 물은 적이 없으면
 *     null. 한 건에 {@code progress.ask.interval}에 한 번이다(06 REP-6b)
 * @param canOpenDefectConfirm 지금 호실 단계에서 하자 확인 요청을 열 수 있는지(03 K, {@code RequestOpening}). 거주 종료 뒤에는
 *     거짓이라 보충이 막힌다. 재계산이면 null
 * @param canOpenCloseConfirm 지금 호실 단계에서 종료 확인 요청을 열 수 있는지. 거주 종료 뒤에는 거짓이라 종료 요청이 막힌다(decisions
 *     2026-11-19 "거주 종료 뒤 수리 종료 확인은 열 수 없다"). 재계산이면 null
 * @param canOpenProgressCheck 지금 호실 단계에서 진행 상황 묻기 요청을 열 수 있는지. 거주 종료 뒤에는 거짓이라 묻기가 막힌다(03 D "진행 상황
 *     묻기: 거주 중"). 재계산이면 null
 * @param hiddenFromCounterpart 상대 측이 이 기록을 볼 수 없는지. 어느 쪽이든 공유를 그만둔 뒤에 등록한 기록이면 참이다(03 불변식 18 "그만둔 뒤
 *     남긴 기록은 상대에게 보이지 않는다(양방향)"). 볼 수 없는 기록에는 상대가 행동할 수 없다(안내서 8-3). 재계산이면 null
 * @param canCloseAlone 행동하는(보는) 측이 지금 종료 요청 없이 혼자 종료할 수 있는지(03 1-1 수리 종료 이유: 물을 상대가 처음부터 없음, 06
 *     REP-5e). 판단은 {@code WaitService.canGoSoloDirectly(CLOSE_CONFIRM)} 그대로이고, 그 이유가 성립한 뒤 이 건에
 *     행동하는 측에게 보이는 상대 행동이 있으면 거짓이다(03 1-4). 측마다 달라 혼자 종료할 때와 상세 조회만 채운다. 그 밖과 재계산이면 null
 * @param situationCheck [상황이 바뀌었어요] 확인 요청 값(마지막으로 켠 시각, 지금 열 수 있는지, 06 REP-6d). null이 아니다
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
    Instant lastProgressAskAt,
    Boolean canOpenDefectConfirm,
    Boolean canOpenCloseConfirm,
    Boolean canOpenProgressCheck,
    Boolean hiddenFromCounterpart,
    Boolean canCloseAlone,
    SituationCheck situationCheck) {

  /** 아직 없는 기록. 하자 등록의 지금 상태이고 재계산의 시작점이다(요청할 때만 아는 값은 모름). */
  public static final RepairState NONE =
      new RepairState(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null);

  public RepairState {
    Objects.requireNonNull(situationCheck, "situationCheck");
  }

  /** [상황이 바뀌었어요]를 켠 적이 없는 상태({@link SituationCheck#NONE}). */
  public RepairState(
      RepairStatus status,
      AckState defectAck,
      MoneyStatus moneyStatus,
      PartySide registeredBy,
      Integer contentNo,
      Instant lastRerequestAt,
      ClosedBy closedBy,
      PartySide closeRequestedBy,
      Instant lastProgressAskAt,
      Boolean canOpenDefectConfirm,
      Boolean canOpenCloseConfirm,
      Boolean canOpenProgressCheck,
      Boolean hiddenFromCounterpart,
      Boolean canCloseAlone) {
    this(
        status,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closedBy,
        closeRequestedBy,
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        SituationCheck.NONE);
  }

  /** 혼자 종료할 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean closeAloneBlocked() {
    return Boolean.FALSE.equals(canCloseAlone);
  }

  /** 혼자 끝낸 처리 완료인가(처리 완료(○○ 확인), 03 D-2). 늦은 종료 확인·이견은 이때만 받는다(REP-5f). */
  public boolean closedAlone() {
    return status == RepairStatus.DONE && closedBy != null && closedBy != ClosedBy.BOTH;
  }

  /** 혼자 종료할 수 있는지를 채운다(혼자 종료할 때와 상세 조회). */
  public RepairState withCanCloseAlone(Boolean value) {
    return new RepairState(
        status,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closedBy,
        closeRequestedBy,
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        value,
        situationCheck);
  }

  /** [상황이 바뀌었어요] 값을 바꾼다(진행 상황을 남길 때 열 수 있는지를 채우거나, 켜서 남긴 시각을 남김). */
  public RepairState withSituationCheck(SituationCheck next) {
    return new RepairState(
        status,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closedBy,
        closeRequestedBy,
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        next);
  }

  /**
   * 요청할 때 읽은 상태인가. {@code load}({@code RepairStateStore.current})는 {@code canOpen…}을 늘 채우고,
   * 재계산({@code Replay.fold})이면 비어 있다. 재계산이면 지금 설정값으로 보는 간격 검사(7일)를 하지 않는다: 설정을 늘린 뒤 이미 기록된 행동을 다시
   * 거절하면 재계산이 실패한다(#234와 같은 방식, #259 리뷰).
   */
  public boolean atRequest() {
    return canOpenDefectConfirm != null;
  }

  /** 하자 확인 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean defectConfirmBlocked() {
    return Boolean.FALSE.equals(canOpenDefectConfirm);
  }

  /** 종료 확인 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean closeConfirmBlocked() {
    return Boolean.FALSE.equals(canOpenCloseConfirm);
  }

  /** 진행 상황 묻기 요청을 열 수 없다고 확인됐나. 모르면(재계산) 거짓이다. */
  public boolean progressCheckBlocked() {
    return Boolean.FALSE.equals(canOpenProgressCheck);
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
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        situationCheck);
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
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        situationCheck);
  }

  /** 진행 상황을 물었다(REP-6b). 수리 진행·하자 인정은 그대로다. */
  public RepairState withLastProgressAskAt(Instant at) {
    return new RepairState(
        status,
        defectAck,
        moneyStatus,
        registeredBy,
        contentNo,
        lastRerequestAt,
        closedBy,
        closeRequestedBy,
        at,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        situationCheck);
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
        lastProgressAskAt,
        canOpenDefectConfirm,
        canOpenCloseConfirm,
        canOpenProgressCheck,
        hiddenFromCounterpart,
        canCloseAlone,
        situationCheck);
  }
}
