package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RoomGateReader} 가짜(07 1주차 2일차). DB 없이 메모리에 적어 둔 값으로 답하고, 잠그지는 않는다.
 *
 * <p>테스트 코드다. 앱과 {@code @SpringBootTest}는 진짜 {@code JpaRoomGateReader}(room, #143)를 쓰고, DB 없는
 * 테스트({@code ActionPipelineFixture})만 이 가짜를 {@code new}로 만들어 쓴다(빈이 아님).
 *
 * <p>실제와 다른 점: 잠그지 않는다. 링크·서버·운영 행동은 호실이 있는지 보지 않는다(실제는 없는 호실이면 {@code NOT_FOUND}일 수 있음).
 *
 * <pre>{@code
 * gates.member(roomId, memberId, PartySide.TENANT);                       // 거주 중, 막히는 것 없음
 * gates.put(memberId, new RoomGate(roomId, PartySide.TENANT, true, false, false, true)); // 정산 완료
 * }</pre>
 */
public class FakeRoomGateReader implements RoomGateReader {

  private final Map<Key, RoomGate> members = new ConcurrentHashMap<>();
  private final Map<UUID, RoomGate> rooms = new ConcurrentHashMap<>();
  // 초대 자리(호실, 자리 ID) → 그 자리의 측. Key의 두 번째 칸은 자리 ID다
  private final Map<Key, PartySide> seats = new ConcurrentHashMap<>();

  /** 잠그지 않는 가짜라 {@link #lockAndRead}와 같다. */
  @Override
  public RoomGate read(UUID roomRecordId, Actor actor) {
    return lockAndRead(roomRecordId, actor);
  }

  @Override
  public RoomGate lockAndRead(UUID roomRecordId, Actor actor) {
    if (actor.isMember()) {
      RoomGate gate = members.get(new Key(roomRecordId, actor.memberId()));
      if (gate == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      return gate;
    }
    RoomGate room = rooms.getOrDefault(roomRecordId, RoomGate.open(roomRecordId, null));
    return new RoomGate(
        roomRecordId,
        actor.side(),
        room.settled(),
        room.joinedAway(),
        false, // 공유 중단은 회원 기준이라 링크·서버·운영 주체면 거짓(09 3-1 RoomGate)
        room.ended());
  }

  /**
   * 초대 자리로 잠근다. {@link #invitee}로 적어 둔 자리만 되고 나머지는 {@code NOT_FOUND}다. 호실 상태는 {@link #putRoom}의 값을
   * 쓴다.
   */
  @Override
  public RoomGate lockAndReadAsInvitee(UUID roomRecordId, Actor actor, UUID partyId) {
    if (!actor.isMember()) {
      throw new IllegalArgumentException("초대 자리로 잠그는 요청은 회원 주체만 써요");
    }
    PartySide side = seats.get(new Key(roomRecordId, partyId));
    if (side == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return rooms.getOrDefault(roomRecordId, RoomGate.open(roomRecordId, null)).asInvitee(side);
  }

  /** 이 호실의 초대 자리로 적어 둔다({@link #lockAndReadAsInvitee}가 받는 자리). */
  public void invitee(UUID roomRecordId, UUID partyId, PartySide side) {
    seats.put(new Key(roomRecordId, partyId), side);
  }

  /** 거주 중이고 막히는 것이 없는 호실의 당사자로 적어 둔다. 가장 흔한 경우다. */
  public RoomGate member(UUID roomRecordId, UUID memberId, PartySide side) {
    return put(memberId, RoomGate.open(roomRecordId, side));
  }

  /** 상태를 직접 정해 적어 둔다(정산 완료, 합류 전 기록, 공유 중단, 거주 종료). */
  public RoomGate put(UUID memberId, RoomGate gate) {
    members.put(new Key(gate.roomRecordId(), memberId), gate);
    return gate;
  }

  /** 링크·서버·운영 행동이 읽을 호실 상태를 정한다. 적지 않으면 막히는 것이 없는 호실로 본다. */
  public RoomGate putRoom(RoomGate gate) {
    rooms.put(gate.roomRecordId(), gate);
    return gate;
  }

  /** 테스트끼리 값이 섞이지 않게 비운다. */
  public void clear() {
    members.clear();
    rooms.clear();
    seats.clear();
  }

  private record Key(UUID roomRecordId, UUID id) {}
}
