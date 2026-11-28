package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * {@link RoomSettlement}의 구현. {@code room_record.status}·{@code settled_at}·{@code delete_due_at}을
 * 쓴다.
 */
@Service
class JpaRoomSettlement implements RoomSettlement {

  private final RoomRecordRepository rooms;
  private final Duration keepAfter;

  JpaRoomSettlement(
      RoomRecordRepository rooms,
      @Value("${app.retention.after-settled:365d}") Duration keepAfter) {
    this.rooms = Objects.requireNonNull(rooms, "rooms");
    if (keepAfter.isNegative() || keepAfter.isZero()) {
      throw new IllegalArgumentException("app.retention.after-settled는 0보다 커야 해요");
    }
    this.keepAfter = keepAfter;
  }

  @Override
  public void settle(UUID roomRecordId, Instant at) {
    rooms
        .findById(roomRecordId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))
        .settle(Objects.requireNonNull(at, "at"), keepAfter);
  }
}
