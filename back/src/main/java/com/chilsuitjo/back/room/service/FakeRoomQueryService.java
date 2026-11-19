package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 호실 조회 가짜(07 1주차 2일차). DB 없이 메모리에 적어 둔 값으로 답한다.
 *
 * <p>실제 구현(ROOM-3 부위)이 들어오면 이 클래스를 지운다. 다른 도메인은 {@link RoomQueryService}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeRoomQueryService implements RoomQueryService {

  private final Map<UUID, Part> parts = new ConcurrentHashMap<>();

  @Override
  public RoomPartSummary findPart(UUID roomRecordId, UUID roomPartId) {
    Part part = parts.get(roomPartId);
    if (part == null || !part.roomRecordId().equals(roomRecordId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return part.summary();
  }

  /** 이 호실 기록의 부위로 적어 둔다. */
  public RoomPartSummary seedPart(UUID roomRecordId, UUID roomPartId, String name) {
    return put(roomRecordId, roomPartId, new RoomPartSummary(name, false));
  }

  /** 이 호실 기록의 숨긴 부위로 적어 둔다. */
  public RoomPartSummary seedHiddenPart(UUID roomRecordId, UUID roomPartId, String name) {
    return put(roomRecordId, roomPartId, new RoomPartSummary(name, true));
  }

  private RoomPartSummary put(UUID roomRecordId, UUID roomPartId, RoomPartSummary summary) {
    parts.put(roomPartId, new Part(roomRecordId, summary));
    return summary;
  }

  private record Part(UUID roomRecordId, RoomPartSummary summary) {}
}
