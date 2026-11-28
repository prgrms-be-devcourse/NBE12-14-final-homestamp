package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.room.dto.RoomForLink;
import com.chilsuitjo.back.room.dto.RoomRegion;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RegionRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link RoomLinkQuery}의 진짜 구현. 호실 기록·자리·법정동을 읽기만 한다. */
@Service
@RequiredArgsConstructor
public class JpaRoomLinkQuery implements RoomLinkQuery {

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;
  private final RegionRepository regions;

  @Override
  @Transactional(readOnly = true)
  public Optional<RoomForLink> forLink(UUID roomRecordId, UUID partyId) {
    return rooms
        .findById(roomRecordId)
        .flatMap(room -> seatOf(room, partyId).map(seat -> view(room, seat)));
  }

  /** 그 호실의 자리. 다른 호실의 자리 ID는 없는 자리와 같게 빈 값이다. */
  private Optional<Party> seatOf(RoomRecord room, UUID partyId) {
    return parties.findById(partyId).filter(seat -> seat.getRoomRecordId().equals(room.getId()));
  }

  private RoomForLink view(RoomRecord room, Party seat) {
    return new RoomForLink(
        seat.getSide(),
        region(room.getRegionCode()),
        seat.getStatus(),
        room.getStatus(),
        room.isSettled(),
        room.isJoinedAway());
  }

  private RoomRegion region(String code) {
    // room_record.region_code는 region을 가리키는 FK라 없을 수 없다
    return RoomRegion.of(
        regions
            .findById(code)
            .orElseThrow(() -> new IllegalStateException("코드표에 없는 법정동이에요: " + code)));
  }
}
