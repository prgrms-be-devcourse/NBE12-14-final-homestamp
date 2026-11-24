package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.AddPartRequest;
import com.chilsuitjo.back.room.dto.PartChanged;
import com.chilsuitjo.back.room.dto.RoomPartView;
import com.chilsuitjo.back.room.dto.TogglePartRequest;
import com.chilsuitjo.back.room.service.RoomPartService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 호실 부위 API(06 4-3, ROOM-3). 상태를 바꾸는 요청은 {@code Idempotency-Key}가 필요하고, 같은 키로 다시 오면 {@code
 * Idempotent-Replayed: true}를 붙인다(06 4-0). 목록 조회(GET)는 키를 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class RoomPartController {

  static final String REPLAYED = "Idempotent-Replayed";

  private final RoomPartService parts;

  /** 부위 목록. 숨긴 부위도 {@code hidden: true}로 들어 있다(ROOM-3a). */
  @GetMapping("/api/rooms/{roomId}/parts")
  public List<RoomPartView> list(@PathVariable UUID roomId, @LoginMember UUID memberId) {
    return parts.list(roomId, memberId);
  }

  /** 부위 추가. 201과 만든 부위. */
  @PostMapping("/api/rooms/{roomId}/parts")
  public ResponseEntity<RoomPartView> add(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody AddPartRequest request) {
    return respond(HttpStatus.CREATED, parts.add(roomId, memberId, idempotencyKey, request));
  }

  /** 숨기기. 200과 숨긴 뒤 부위. */
  @PostMapping("/api/rooms/{roomId}/parts/{partId}/hide")
  public ResponseEntity<RoomPartView> hide(
      @PathVariable UUID roomId,
      @PathVariable UUID partId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return respond(HttpStatus.OK, parts.hide(roomId, partId, memberId, idempotencyKey));
  }

  /** 다시 보이기(ROOM-3b). 200과 다시 보이게 한 뒤 부위. */
  @PostMapping("/api/rooms/{roomId}/parts/{partId}/show")
  public ResponseEntity<RoomPartView> show(
      @PathVariable UUID roomId,
      @PathVariable UUID partId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return respond(HttpStatus.OK, parts.show(roomId, partId, memberId, idempotencyKey));
  }

  /** 설비·가전 켜고 끄기. 200과 바꾼 뒤 부위. */
  @PostMapping("/api/rooms/{roomId}/parts/{partId}/toggle")
  public ResponseEntity<RoomPartView> toggle(
      @PathVariable UUID roomId,
      @PathVariable UUID partId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody TogglePartRequest request) {
    return respond(
        HttpStatus.OK, parts.toggle(roomId, partId, memberId, idempotencyKey, request.enabled()));
  }

  private static ResponseEntity<RoomPartView> respond(HttpStatus status, PartChanged changed) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    if (changed.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(changed.part());
  }
}
