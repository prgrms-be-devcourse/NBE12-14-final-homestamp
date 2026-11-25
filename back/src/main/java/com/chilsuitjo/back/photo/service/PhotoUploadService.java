package com.chilsuitjo.back.photo.service;

import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.UploadSlotResponse;
import com.chilsuitjo.back.photo.dto.UploadTicketsRequest;
import com.chilsuitjo.back.photo.dto.UploadTicketsResponse;
import com.chilsuitjo.back.photo.repository.UploadPostSigner;
import com.chilsuitjo.back.photo.rule.PhotoUploadFileRules;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/** 사진 업로드 표와 S3 업로드 폼을 발급한다(06 4-5). */
@Service
public class PhotoUploadService {

  private final RoomAccessService roomAccessService;
  private final PhotoUploadQuotaService quota;
  private final UploadPostSigner postSigner;
  private final PhotoUploadTicketSigner ticketSigner;
  private final Clock clock;
  private final Duration ticketTtl;
  private final long maxFileBytes;

  PhotoUploadService(
      RoomAccessService roomAccessService,
      PhotoUploadQuotaService quota,
      UploadPostSigner postSigner,
      PhotoUploadTicketSigner ticketSigner,
      Clock clock,
      @Value("${upload.ticket.ttl}") Duration ticketTtl,
      @Value("${upload.size.max}") DataSize maxFileSize) {
    this.roomAccessService = roomAccessService;
    this.quota = quota;
    this.postSigner = postSigner;
    this.ticketSigner = ticketSigner;
    this.clock = clock;
    this.ticketTtl = ticketTtl;
    this.maxFileBytes = maxFileSize.toBytes();
  }

  @Transactional
  public UploadTicketsResponse issue(UUID roomId, UUID memberId, UploadTicketsRequest request) {
    validateIssueRequest(roomId, memberId, request);
    quota.reserve(memberId, request.files().size());

    // JWT 만료는 초 단위이므로 응답의 만료 시각도 같은 정밀도로 맞춘다.
    Instant expiresAt = clock.instant().plus(ticketTtl).truncatedTo(ChronoUnit.SECONDS);
    List<UploadSlotResponse> slots = new ArrayList<>(request.files().size());
    for (var file : request.files()) {
      String prefix = "pending/" + roomId + "/" + Uuids.v7() + "/";
      String originalKey = prefix + "original.jpg";
      String thumbKey = prefix + "thumb.jpg";
      var original =
          postSigner.createPresignedPost(originalKey, file.originalBytes(), file.originalSha256());
      var thumb = postSigner.createPresignedPost(thumbKey, file.thumbBytes(), file.thumbSha256());
      String ticket = ticketSigner.sign(roomId, memberId, originalKey, thumbKey, file, expiresAt);
      slots.add(new UploadSlotResponse(slots.size(), ticket, expiresAt, original, thumb));
    }
    return new UploadTicketsResponse(List.copyOf(slots));
  }

  public void validateIssueRequest(UUID roomId, UUID memberId, UploadTicketsRequest request) {
    var room = roomAccessService.require(roomId, memberId);
    if (room.myStatus() == PartyStatus.WITHDRAWN) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    if (room.roomStatus() == RoomStatus.SETTLED) {
      throw new BusinessException(ErrorCode.SETTLED);
    }
    if (room.joinedIntoRoomId() != null) {
      throw new BusinessException(ErrorCode.JOINED_RECORD);
    }
    // 거주 종료 뒤에도 퇴거 점검·정산 증빙이 가능하므로 붙일 대상의 제한은 제출 때 확인한다.
    for (var file : request.files()) {
      validateFile(file.originalBytes(), file.originalSha256());
      validateFile(file.thumbBytes(), file.thumbSha256());
    }
  }

  private void validateFile(long bytes, String checksum) {
    if (bytes < 1 || bytes > maxFileBytes || !PhotoUploadFileRules.isSha256Base64(checksum)) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
  }
}
