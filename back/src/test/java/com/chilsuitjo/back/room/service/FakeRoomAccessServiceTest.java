package com.chilsuitjo.back.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ROOM-2a: 당사자가 아니면 남의 호실은 있는지도 알 수 없다. */
class FakeRoomAccessServiceTest {

  private final FakeRoomAccessService service = new FakeRoomAccessService();
  private final UUID roomRecordId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  @Test
  void 적어_둔_당사자는_내_측과_상태를_돌려받는다() {
    service.seed(roomRecordId, memberId, PartySide.TENANT);

    RoomAccess access = service.require(roomRecordId, memberId);

    assertThat(access.roomRecordId()).isEqualTo(roomRecordId);
    assertThat(access.memberId()).isEqualTo(memberId);
    assertThat(access.side()).isEqualTo(PartySide.TENANT);
    assertThat(access.myStatus()).isEqualTo(PartyStatus.CONNECTED);
    assertThat(access.roomStatus()).isEqualTo(RoomStatus.LIVING);
    assertThat(access.joinedIntoRoomId()).isNull();
    assertThat(access.counterpartStatus()).isEqualTo(PartyStatus.CONNECTED);
    assertThat(access.sharingStoppedAt()).isNull();
  }

  @Test
  void 당사자가_아니면_NOT_FOUND() {
    service.seed(roomRecordId, memberId, PartySide.TENANT);

    assertThatThrownBy(() -> service.require(roomRecordId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  void 없는_호실도_NOT_FOUND() {
    assertThatThrownBy(() -> service.require(UUID.randomUUID(), memberId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  void 상태를_직접_정해_적어_둘_수_있다() {
    Instant stoppedAt = Instant.parse("2026-11-17T00:00:00Z");
    service.put(
        new RoomAccess(
            roomRecordId,
            memberId,
            UUID.randomUUID(),
            PartySide.LANDLORD,
            PartyStatus.CONNECTED,
            RoomStatus.ENDED,
            null,
            PartyStatus.SHARING_STOPPED,
            stoppedAt));

    RoomAccess access = service.require(roomRecordId, memberId);

    assertThat(access.side()).isEqualTo(PartySide.LANDLORD);
    assertThat(access.roomStatus()).isEqualTo(RoomStatus.ENDED);
    assertThat(access.counterpartStatus()).isEqualTo(PartyStatus.SHARING_STOPPED);
    assertThat(access.sharingStoppedAt()).isEqualTo(stoppedAt);
  }
}
