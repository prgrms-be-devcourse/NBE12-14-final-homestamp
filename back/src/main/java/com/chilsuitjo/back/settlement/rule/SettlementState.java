package com.chilsuitjo.back.settlement.rule;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.rule.SoloReason;
import java.time.Instant;

/**
 * 정산 하나의 상태 값({@link SettlementTransitions}가 다룬다).
 *
 * <p><b>요청할 때만 아는 값</b>(아래 {@code Facts})은 호실·응답 요청의 사실이라 {@code load}가 그때 읽어 채운다. 행동 기록만으로 다시 계산할
 * 때는 null이고, 그때는 검사하지 않는다(기록된 행동은 그때 서버가 이미 확인했다).
 *
 * @param opened 정산이 열렸는지
 * @param finishRequestedBy 지금 열린(기다림·미확인) 정산 끝내기 요청을 보낸 측. 없으면 null
 * @param settled 정산 완료가 됐는지
 * @param facts 요청할 때만 아는 값. 재계산이면 null
 */
public record SettlementState(
    boolean opened, PartySide finishRequestedBy, boolean settled, Facts facts) {

  /** 아직 열리지 않은 정산. 재계산의 시작점이다. */
  public static final SettlementState NONE = new SettlementState(false, null, false, null);

  /** 열린 정산(끝내기 요청 없음). */
  public static final SettlementState OPENED = new SettlementState(true, null, false, null);

  /**
   * 요청할 때 읽은 호실·요청 사실.
   *
   * @param ended 호실이 거주 종료 뒤인지(정산 끝내기는 거주 종료 뒤에만, 03 A)
   * @param snapshotNo 지금 정산 모습 번호(끝내기 확인의 본 번호와 비교, 06 SET-5e)
   * @param counterpartConnected 상대가 연결됐는지(끝내기 요청은 연결한 상대에게만, 03 1-4)
   * @param sharingStopped 어느 쪽이든 공유 중단 제한이 살아 있는지(공유를 그만둔 동안은 끝내기가 없음, CONS-3g)
   * @param lastNotYetAt 이 사람이 보낸 끝내기 요청에 상대가 [아직 안 끝났어요]로 답한 마지막 시각(다시 요청 7일, 06 SET-5c). 없으면 null
   * @param soloReason 지금 혼자 끝낼 수 있으면 그 이유(06 SET-5d, 1-1), 아니면 null
   */
  public record Facts(
      boolean ended,
      int snapshotNo,
      boolean counterpartConnected,
      boolean sharingStopped,
      Instant lastNotYetAt,
      SoloReason soloReason) {}

  public SettlementState withFinishRequestedBy(PartySide side) {
    return new SettlementState(opened, side, settled, facts);
  }

  public SettlementState asSettled() {
    return new SettlementState(opened, null, true, facts);
  }
}
