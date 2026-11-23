package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.PartyRepository;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 호실 권한 검사(06 ROOM-2). 이 호실 기록의 당사자 자리에 이 회원이 있는지 DB에서 본다. 잠그지 않으므로 조회 화면처럼 잠금이 필요 없는 곳에서 쓴다. 상태를
 * 바꾸는 요청은 pipeline이 잠근 뒤 {@link JpaRoomGateReader}로 다시 본다(09 3-1 "계약").
 */
@Service
@RequiredArgsConstructor
public class JpaRoomAccessService implements RoomAccessService {

  private final RoomRecordRepository rooms;
  private final PartyRepository parties;

  @Override
  @Transactional(readOnly = true)
  public RoomAccess require(UUID roomRecordId, UUID memberId) {
    RoomRecord room =
        rooms.findById(roomRecordId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    RoomSeats seats = new RoomSeats(room, parties.findByRoomRecordId(roomRecordId));
    Party mine = seats.seatOf(memberId);
    return new RoomAccess(
        roomRecordId,
        memberId,
        mine.getId(),
        mine.getSide(),
        mine.getStatus(),
        room.getStatus(),
        room.getJoinedIntoId(),
        seats.counterpartOf(mine).getStatus(),
        seats.earliestSharingStoppedAt());
  }
}
