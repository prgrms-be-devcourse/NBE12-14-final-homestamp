package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.UUID;

/**
 * pipeline이 호실 단위 거절을 판단하는 값. {@code RoomGateReader}(room 구현)가 호실 기록 행을 잠근 <b>뒤</b> 읽어 만든다. 도메인은
 * 만들지 않는다(09 3-1 "계약").
 *
 * <p>{@code action}은 {@code room}을 import할 수 없어서 room의 상태 값({@code RoomStatus}, {@code
 * PartyStatus}) 대신 예·아니오만 담는다.
 *
 * @param side 회원·링크 행동이면 그 측, 서버·운영이면 null
 * @param settled 정산 완료(읽기 전용)
 * @param joinedAway 합류 전 기록(새 행동을 받지 않음)
 * @param mySharingStopped 행동하는 회원이 공유를 그만둔 상태
 * @param ended 거주 종료(거주 종료·정산 완료). 새 수리 건을 받지 않음(03 불변식 9, 06 REP-1c)
 */
public record RoomGate(
    UUID roomRecordId,
    PartySide side,
    boolean settled,
    boolean joinedAway,
    boolean mySharingStopped,
    boolean ended) {

  /** 거주 중이고 막히는 것이 없는 호실. */
  public static RoomGate open(UUID roomRecordId, PartySide side) {
    return new RoomGate(roomRecordId, side, false, false, false, false);
  }
}
