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

  /**
   * 초대 자리로 들어온 요청({@code ActionBatch.asInvitee})의 1단계. 호실 기록 행을 {@code FOR UPDATE}로 잠근 뒤, {@code
   * partyId}가 이 호실의 자리이면 그 자리의 측으로 {@code invitee}인 {@link RoomGate}를 돌려주고, 아니면 {@code NOT_FOUND}다.
   *
   * <p>아직 연결하지 않은 초대받은 회원은 당사자가 아니라 {@link #lockAndRead}로는 {@code NOT_FOUND}가 난다(ROOM-2a). 그래서
   * 연결·거절 요청만 이 길로 잠근다(#163, #249). 자리 상태({@code INVITED}인지)와 번호(초대 번호 = 인증 번호, ROOM-5b {@code
   * PHONE_MISMATCH})는 여기서 보지 않고 room의 Party 규칙이 잠금 뒤에 본다("막는 조건 하나에 오류 하나"). 그래서 이미 연결한 뒤 같은 키로 다시
   * 온 요청도 이 길을 지나 처음 결과를 돌려받는다.
   *
   * @param actor 회원 주체만
   */
  RoomGate lockAndReadAsInvitee(UUID roomRecordId, Actor actor, UUID partyId);

  /**
   * 잠그지 않고 읽는다. 값과 거절({@code NOT_FOUND})은 {@link #lockAndRead}와 같다. 상세 조회가 {@code
   * availableActions}(06 4-0)를 계산할 때 쓴다. 상태를 바꾸는 요청은 이 값으로 판단하지 않는다(09 3-1 "계약": pipeline이 잠근 뒤 다시
   * 본다).
   */
  RoomGate read(UUID roomRecordId, Actor actor);
}
