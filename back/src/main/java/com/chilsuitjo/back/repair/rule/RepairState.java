package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.rule.Answerable;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import java.time.Instant;

/**
 * 수리 건 하나의 상태 값({@link RepairTransitions}가 다룬다). 하자 인정({@code defectAck})과 수리 진행({@code status})은
 * 따로 간다(03 D). 아직 없는 기록이면 모든 칸이 비어 있다({@link #NONE}).
 *
 * @param status 수리 진행(시작 전 → 처리 중 → 종료 확인 대기 → 처리 완료)
 * @param defectAck 하자 인정(확인 기다림 / 맞아요 / 보류 / 이견 / 확인 없음)
 * @param moneyStatus 수리비 부담(미정에서 시작)
 * @param registeredBy 등록한 측. 하자에 답하는 쪽은 그 반대 측이고, 보충하는 쪽은 이 측이다(REP-2)
 * @param contentNo 지금 판 번호({@code repair_content.no}). 응답할 때 본 판 번호와 맞댄다. 아직 없는 기록이면 null
 * @param lastRerequestAt 마지막으로 보충한 시각(행동 기록 {@code SUPPLEMENT}가 원본). 보충한 적이 없으면 null
 * @param canOpenDefectConfirm 지금 호실 단계에서 하자 확인 요청을 열 수 있는지({@code waitService.canOpen}, 03 K). 거주
 *     종료 뒤에는 거짓이라 보충이 막힌다
 * @param hiddenFromCounterpart 상대 측이 이 기록을 볼 수 없는지. 어느 쪽이든 공유를 그만둔 뒤에 등록한 기록이면 참이다(03 불변식 18 "그만둔 뒤
 *     남긴 기록은 상대에게 보이지 않는다(양방향)"). 볼 수 없는 기록에는 답할 수 없다(안내서 8-3)
 */
public record RepairState(
    RepairStatus status,
    AckState defectAck,
    MoneyStatus moneyStatus,
    PartySide registeredBy,
    Integer contentNo,
    Instant lastRerequestAt,
    boolean canOpenDefectConfirm,
    boolean hiddenFromCounterpart) {

  /** 아직 없는 기록. 하자 등록의 지금 상태이고 재계산의 시작점이다. */
  public static final RepairState NONE =
      new RepairState(null, null, null, null, null, null, false, false);

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
        canOpenDefectConfirm,
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
        canOpenDefectConfirm,
        hiddenFromCounterpart);
  }
}
