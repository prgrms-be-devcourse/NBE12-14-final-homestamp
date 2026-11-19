package com.chilsuitjo.back.notification.service;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.PartyFacts;
import com.chilsuitjo.back.notification.dto.RoomParties;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 자리 상태 가짜. 테스트가 정해 둔 두 자리를 그대로 돌려준다.
 *
 * <p>room의 실제 구현(ROOM-2·4·5)이 들어오면 이 클래스를 지운다(같은 타입 빈이 둘이면 주입이 실패한다). 다른 도메인은 {@link
 * CounterpartReader}만 쓰므로 바뀌지 않는다.
 */
@Service
public class FakeCounterpartReader implements CounterpartReader {

  private final Map<UUID, Map<PartySide, PartyFacts>> rooms = new ConcurrentHashMap<>();

  @Override
  public RoomParties read(UUID roomRecordId, PartySide mySide) {
    Map<PartySide, PartyFacts> sides = rooms.get(roomRecordId);
    if (sides == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    PartySide other = mySide == PartySide.TENANT ? PartySide.LANDLORD : PartySide.TENANT;
    return new RoomParties(
        sides.getOrDefault(mySide, PartyFacts.empty()),
        sides.getOrDefault(other, PartyFacts.empty()));
  }

  /** 이 호실의 한 자리를 적어 둔다. 적지 않은 자리는 빈 자리(초대 전)다. */
  public void seed(UUID roomRecordId, PartySide side, PartyFacts facts) {
    rooms.computeIfAbsent(roomRecordId, id -> new EnumMap<>(PartySide.class)).put(side, facts);
  }
}
