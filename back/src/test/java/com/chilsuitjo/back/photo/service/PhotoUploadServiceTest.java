package com.chilsuitjo.back.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.UploadFileRequest;
import com.chilsuitjo.back.photo.dto.UploadTicketsRequest;
import com.chilsuitjo.back.photo.repository.UploadPostSigner;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.util.unit.DataSize;

class PhotoUploadServiceTest {

  private static final UUID ROOM_ID = UUID.randomUUID();
  private static final UUID MEMBER_ID = UUID.randomUUID();
  private static final UUID PARTY_ID = UUID.randomUUID();
  private static final DataSize SIZE_LIMIT = DataSize.ofMegabytes(10);
  private static final String CHECKSUM = Base64.getEncoder().encodeToString(new byte[32]);
  private static final UploadFileRequest FILE =
      new UploadFileRequest(SIZE_LIMIT.toBytes(), 1, CHECKSUM, CHECKSUM, null);

  @ParameterizedTest
  @EnumSource(
      value = RoomStatus.class,
      names = {"LIVING", "ENDED"})
  void allowsEligibleRoomStates(RoomStatus status) {
    service(status, PartyStatus.CONNECTED, null)
        .validateIssueRequest(ROOM_ID, MEMBER_ID, request(FILE));
  }

  @Test
  void rejectsMissingRoomAccess() {
    var service =
        validationService((roomId, memberId) -> {
          throw new BusinessException(ErrorCode.NOT_FOUND);
        });

    assertRejected(service, FILE, ErrorCode.NOT_FOUND);
  }

  @Test
  void rejectsWithdrawnParty() {
    assertRejected(
        service(RoomStatus.LIVING, PartyStatus.WITHDRAWN, null), FILE, ErrorCode.NOT_FOUND);
  }

  @Test
  void rejectsSettledAndJoinedAwayRooms() {
    UUID joinedInto = UUID.randomUUID();
    assertRejected(
        service(RoomStatus.SETTLED, PartyStatus.CONNECTED, joinedInto), FILE, ErrorCode.SETTLED);
    assertRejected(
        service(RoomStatus.LIVING, PartyStatus.CONNECTED, joinedInto),
        FILE,
        ErrorCode.JOINED_RECORD);
  }

  @Test
  void rejectsInvalidOriginalOrThumbnailMetadata() {
    var service = service(RoomStatus.LIVING, PartyStatus.CONNECTED, null);
    assertRejected(
        service,
        new UploadFileRequest(SIZE_LIMIT.toBytes() + 1, 1, CHECKSUM, CHECKSUM, null),
        ErrorCode.VALIDATION_FAILED);
    assertRejected(
        service,
        new UploadFileRequest(1, 0, CHECKSUM, CHECKSUM, null),
        ErrorCode.VALIDATION_FAILED);
    assertRejected(
        service,
        new UploadFileRequest(1, 1, CHECKSUM, "!".repeat(44), null),
        ErrorCode.VALIDATION_FAILED);
  }

  private static void assertRejected(
      PhotoUploadService service, UploadFileRequest file, ErrorCode code) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.validateIssueRequest(ROOM_ID, MEMBER_ID, request(file)))
        .satisfies(error -> assertThat(error.errorCode()).isEqualTo(code));
  }

  private static UploadTicketsRequest request(UploadFileRequest file) {
    return new UploadTicketsRequest(List.of(file));
  }

  private static PhotoUploadService service(
      RoomStatus status, PartyStatus myStatus, UUID joinedInto) {
    return validationService((roomId, memberId) ->
        new RoomAccess(
            roomId,
            memberId,
            PARTY_ID,
            PartySide.TENANT,
            myStatus,
            status,
            joinedInto,
            PartyStatus.CONNECTED,
            null));
  }

  private static PhotoUploadService validationService(RoomAccessService roomAccess) {
    return new PhotoUploadService(
        roomAccess,
        mock(PhotoUploadQuotaService.class),
        mock(UploadPostSigner.class),
        mock(PhotoUploadTicketSigner.class),
        Clock.systemUTC(),
        Duration.ofHours(12),
        SIZE_LIMIT);
  }
}
