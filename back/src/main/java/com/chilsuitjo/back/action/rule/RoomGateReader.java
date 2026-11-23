package com.chilsuitjo.back.action.rule;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import java.util.UUID;

/**
 * 호실 기록 행을 잠그고 잠근 뒤의 호실 상태를 읽는다. {@code action}이 두고 {@code room}이 구현한다({@code room → action} 방향, 09
 * 3장 "고치기"의 {@code Correctable}과 같은 방식).
 *
 * <p>pipeline 1단계가 부른다. 호실 단위 거절은 이 값으로만 판단한다(09 3-1 "계약": 잠금 전에 읽은 값을 쓰면 기다리는 동안 정산 완료·공유 중단이 끼어들
 * 수 있음).
 *
 * <p>실제 구현은 A(ROOM-2)의 {@code JpaRoomGateReader}. DB 없는 테스트만 테스트 코드의 {@code FakeRoomGateReader}를
 * 쓴다.
 */
public interface RoomGateReader {

  /**
   * 호실 기록 행을 {@code FOR UPDATE}로 잠근 뒤 읽는다.
   *
   * <ul>
   *   <li>{@code Actor.member}: 이 호실의 당사자인지 본다. 아니면 {@code NOT_FOUND}(ROOM-2a)
   *   <li>{@code Actor.link}·{@code server}·{@code ops}: 호실 상태만 읽는다({@code side}는 링크면 actor의 측)
   * </ul>
   */
  RoomGate lockAndRead(UUID roomRecordId, Actor actor);
}
