package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.CreateRoomRequest;
import com.chilsuitjo.back.room.dto.RoomCreated;
import com.chilsuitjo.back.room.service.RoomService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 호실 API(06 4-3). */
@RestController
@RequiredArgsConstructor
public class RoomController {

  private final RoomService rooms;

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
}
