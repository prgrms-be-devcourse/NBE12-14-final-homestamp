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
  void 이_호실의_부위는_이름을_돌려받는다() {
    service.seedPart(roomRecordId, roomPartId, "천장");

    RoomPartSummary part = service.findPart(roomRecordId, roomPartId);

    assertThat(part.name()).isEqualTo("천장");
    assertThat(part.hidden()).isFalse();
  }

  @Test
  void 없는_부위는_NOT_FOUND() {
    service.seedPart(roomRecordId, roomPartId, "천장");

    assertNotFound(() -> service.findPart(roomRecordId, UUID.randomUUID()));
  }

  @Test
  void 다른_호실의_부위는_있어도_NOT_FOUND() {
    UUID otherRoomPartId = UUID.randomUUID();
    service.seedPart(UUID.randomUUID(), otherRoomPartId, "천장");

    assertNotFound(() -> service.findPart(roomRecordId, otherRoomPartId));
  }

  @Test
  void 숨긴_부위도_숨김_표시와_함께_돌려받는다() {
    service.seedHiddenPart(roomRecordId, roomPartId, "베란다");

    RoomPartSummary part = service.findPart(roomRecordId, roomPartId);

    assertThat(part.name()).isEqualTo("베란다");
    assertThat(part.hidden()).isTrue();
  }

  private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }
}
