package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.response.CursorPage;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.CreateRoomRequest;
import com.chilsuitjo.back.room.dto.RoomCreated;
import com.chilsuitjo.back.room.dto.RoomDetail;
import com.chilsuitjo.back.room.dto.RoomSummary;
import com.chilsuitjo.back.room.service.RoomService;
import com.chilsuitjo.back.room.service.RoomViewService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 호실 API(06 4-3). */
@RestController
@RequiredArgsConstructor
public class RoomController {

  private final RoomService rooms;
  private final RoomViewService views;

  /** 호실 기록 만들기(ROOM-1). 201 + 호실 기록 ID. 같은 키로 다시 오면 처음 만든 호실을 같은 모양으로 돌려준다(06 4-0). */
  @PostMapping("/api/rooms")
  public ResponseEntity<CreatedResponse> create(
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateRoomRequest request) {
    RoomCreated created = rooms.create(memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(new CreatedResponse(created.roomRecordId()));
  }

  /** 내 호실 목록(ROOM-1, S-03). 커서 방식, 새것부터(06 4-0 "목록"). */
  @GetMapping("/api/rooms")
  public CursorPage<RoomSummary> list(
      @LoginMember UUID memberId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + RoomViewService.DEFAULT_SIZE) int size) {
    return views.list(memberId, cursor, size);
  }

  /** 호실 상세(ROOM-2, S-06). 남의 호실도 {@code NOT_FOUND}(ROOM-2a). */
  @GetMapping("/api/rooms/{id}")
  public RoomDetail detail(@LoginMember UUID memberId, @PathVariable UUID id) {
    return views.detail(id, memberId);
  }
}
