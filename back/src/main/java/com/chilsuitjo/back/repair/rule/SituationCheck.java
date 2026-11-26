package com.chilsuitjo.back.repair.rule;

import java.time.Instant;

/**
 * 수리 건의 [상황이 바뀌었어요] 확인 요청 값(06 REP-6d). 한 건에 다시 요청끼리 {@code rerequest.interval}에 한 번이고, 보충({@code
 * RepairState.lastRerequestAt})과 함께 센다(03 G "다시 확인 요청의 한도", decisions 2026-11-26).
 *
 * @param lastAskedAt 마지막으로 [상황이 바뀌었어요]를 켜서 진행 상황을 남긴 시각(행동 기록 {@code NOTE_PROGRESS}의 payload {@code
 *     asksCheck}가 원본, 누가 남겼든). 없으면 null
 * @param opening 행동하는 측이 지금 요청을 열 수 있는지. 진행 상황을 남길 때만 채우고, 그 밖과 재계산이면 null(그때는 서버가 이미 확인했다고 본다)
 */
public record SituationCheck(Instant lastAskedAt, SituationOpening opening) {

  /** 아직 켠 적이 없고 열 수 있는지도 모른다. */
  public static final SituationCheck NONE = new SituationCheck(null, null);

  public SituationCheck withOpening(SituationOpening next) {
    return new SituationCheck(lastAskedAt, next);
  }
}
