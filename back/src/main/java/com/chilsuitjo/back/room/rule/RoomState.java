package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.global.common.PartySide;

/**
 * {@link RoomTransitions}가 다루는 호실 기록 상태(03 A).
 *
 * <p>{@code status} 말고는 요청할 때 호실을 잠근 뒤 읽는 값이다. 행동 기록으로 다시 계산할 때는 모르므로 null이고, null인 칸은 검사하지
 * 않는다({@code PartyState}와 같은 방식).
 *
 * @param status 호실 상태. 아직 만들지 않은 호실이면 null
 * @param actorSide 행동하는 측
 * @param endRequestOpen 집주인 측이 보낸 거주 종료 요청({@code END_REQUEST})이 열려 있나(기다림·미확인)
 * @param tenantAnswers 세입자 자리가 거주 종료 요청에 답할 수 있나(연결됨·공유 중단, 03 B "예외 둘")
 * @param landlordEndsAlone 집주인 측이 요청 없이 바로 거주 종료할 수 있나({@code WaitService.canGoSoloDirectly}, 03
 *     1-1 "함수 하나"). 집주인 측 요청에서만 채운다
 */
public record RoomState(
    RoomStatus status,
    PartySide actorSide,
    Boolean endRequestOpen,
    Boolean tenantAnswers,
    Boolean landlordEndsAlone) {

  /** 상태만 아는 값(호실 만들기, 다시 계산). */
  public RoomState(RoomStatus status) {
    this(status, null, null, null, null);
  }

  /**
   * [이사했어요]·[이사함]이 바로 거주 종료가 되나. 세입자 측이거나, 집주인 측인데 물을 세입자가 없을 때다(03 A "이사함 기록"). 아니면 거주 종료 요청이다.
   */
  public boolean movesOutNow() {
    return actorSide == PartySide.TENANT || Boolean.TRUE.equals(landlordEndsAlone);
  }

  RoomState withStatus(RoomStatus newStatus, Boolean newEndRequestOpen) {
    return new RoomState(newStatus, actorSide, newEndRequestOpen, tenantAnswers, landlordEndsAlone);
  }
}
