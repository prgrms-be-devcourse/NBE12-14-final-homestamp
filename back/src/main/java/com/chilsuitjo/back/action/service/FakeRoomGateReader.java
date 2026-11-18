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
import org.springframework.stereotype.Component;

/**
 * {@link RoomGateReader} 가짜(07 1주차 2일차). DB 없이 메모리에 적어 둔 값으로 답하고, 잠그지는 않는다.
 *
 * <p>실제 구현(A, ROOM-2)이 들어오면 이 클래스를 지우거나 실제 구현에 {@code @Primary}를 붙인다(같은 타입 빈이 둘이면 주입이 실패한다).
 *
 * <p>실제와 다른 점: 잠그지 않는다. 링크·서버·운영 행동은 호실이 있는지 보지 않는다(실제는 없는 호실이면 {@code NOT_FOUND}일 수 있음).
 *
 * <pre>{@code
 * gates.member(roomId, memberId, PartySide.TENANT);                       // 거주 중, 막히는 것 없음
 * gates.put(memberId, new RoomGate(roomId, PartySide.TENANT, true, false, false, true)); // 정산 완료
 * }</pre>
 */
@Component
public class FakeRoomGateReader implements RoomGateReader {

  private final Map<Key, RoomGate> members = new ConcurrentHashMap<>();
  private final Map<UUID, RoomGate> rooms = new ConcurrentHashMap<>();

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
        room.mySharingStopped(),
        room.ended());
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
  }

  private record Key(UUID roomRecordId, UUID memberId) {}
}
