package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.RoomGate;

/**
 * 내 호실 목록의 다음 할 일 한 줄(06 4-0 "할 수 있는 행동"의 {@code nextStep}). 서버는 코드만 주고 문구는 화면이 고른다. 목록은 무겁지 않게 행동
 * 목록 대신 이 한 줄만 준다. 판단은 상세의 {@code availableActions}와 같은 {@link RoomGate} 값으로 한다.
 *
 * <p>입주 점검 시작·진행 중인 수리 건수·상대 초대는 그 도메인의 읽기 창구나 API가 생긴 뒤 값을 더한다(#190).
 */
public enum NextStep {
  /**
   * 거주가 끝나 정산을 할 차례. 내가 공유를 그만뒀으면 정산 항목·송금·끝내기를 모두 할 수 없어(03 B, pipeline {@code SHARING_STOPPED})
   * 주지 않는다. 상대가 탈퇴했으면 그 제한이 풀려 준다(06 CONS-3p, {@code mySharingStopped}가 거짓).
   *
   * <p>상대가 공유를 그만둔 경우에는 준다. 남은 사람은 정산 끝내기만 막히고(06 CONS-3g, 정산 규칙이 {@code INVALID_STATE}·{@code
   * SOLO_NOT_ALLOWED}로 봄) 정산 항목·보증금·송금 기록은 할 수 있기 때문이다. 그만둔 상대가 탈퇴하면 끝내기 제한도 풀린다(CONS-3q).
   */
  SETTLE,
  /** 정산 완료 또는 합류 전 기록이라 읽기만 한다. */
  READ_ONLY,
  /** 지금 안내할 일이 없다. */
  NONE;

  public static NextStep of(RoomGate gate) {
    if (gate.settled() || gate.joinedAway()) {
      return READ_ONLY;
    }
    if (gate.ended() && !gate.mySharingStopped()) {
      return SETTLE;
    }
    return NONE;
  }
}
