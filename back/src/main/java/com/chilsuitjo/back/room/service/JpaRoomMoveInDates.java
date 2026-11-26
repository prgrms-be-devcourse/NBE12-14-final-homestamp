package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.entity.RoomRecord;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link RoomMoveInDates}의 구현. {@code room_record.move_in_date}를 읽고 쓴다. */
@Service
@RequiredArgsConstructor
class JpaRoomMoveInDates implements RoomMoveInDates {

  private final RoomRecordRepository rooms;

  @Override
  public Optional<LocalDate> find(UUID roomRecordId) {
    return Optional.ofNullable(room(roomRecordId).getMoveInDate());
  }

  @Override
  public void fill(UUID roomRecordId, LocalDate date) {
    room(roomRecordId).fillMoveInDate(date);
  }

  private RoomRecord room(UUID roomRecordId) {
    return rooms
        .findById(roomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }
}
