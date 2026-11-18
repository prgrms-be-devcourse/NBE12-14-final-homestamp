package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomPartSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeRoomQueryServiceTest {

  private final FakeRoomQueryService service = new FakeRoomQueryService();
  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID roomPartId = UUID.randomUUID();

  @Test
  void 적어_둔_부위는_호실_기록과_이름을_돌려받는다() {
    service.seedPart(roomPartId, roomRecordId, "천장");

    RoomPartSummary part = service.findPart(roomPartId);

    assertThat(part.roomRecordId()).isEqualTo(roomRecordId);
    assertThat(part.name()).isEqualTo("천장");
  }

  @Test
  void 없는_부위는_NOT_FOUND() {
    service.seedPart(roomPartId, roomRecordId, "천장");

    assertThatThrownBy(() -> service.findPart(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }
}
