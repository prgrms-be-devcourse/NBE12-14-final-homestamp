package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 권한 검사 가짜(07 1주차 2일차). DB 없이 메모리에 적어 둔 값으로 답한다.
 *
 * <p>실제 구현(ROOM-2)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link RoomAccessService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeRoomAccessService implements RoomAccessService {

  private final Map<Key, RoomAccess> accesses = new ConcurrentHashMap<>();

  @Override
  public RoomAccess require(UUID roomRecordId, UUID memberId) {
    RoomAccess access = accesses.get(new Key(roomRecordId, memberId));
    if (access == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return access;
  }

  /** 거주 중이고 양쪽이 연결된 호실의 당사자로 적어 둔다. 가장 흔한 경우다. */
  public RoomAccess seed(UUID roomRecordId, UUID memberId, PartySide side) {
    return put(
        new RoomAccess(
            roomRecordId,
            memberId,
            UUID.randomUUID(),
            side,
            PartyStatus.CONNECTED,
            RoomStatus.LIVING,
            null,
            PartyStatus.CONNECTED,
            null));
  }

  /** 상태를 직접 정해 적어 둔다(거주 종료, 상대 연결 전, 공유 중단 등). */
  public RoomAccess put(RoomAccess access) {
    accesses.put(new Key(access.roomRecordId(), access.memberId()), access);
    return access;
  }

  private record Key(UUID roomRecordId, UUID memberId) {}
}
