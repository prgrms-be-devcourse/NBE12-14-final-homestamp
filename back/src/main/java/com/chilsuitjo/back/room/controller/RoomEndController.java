package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.common.dto.ReasonRequest;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.MoveOutResult;
import com.chilsuitjo.back.room.service.RoomEndService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거주 종료 API(06 4-3, ROOM-9). 모두 {@code Idempotency-Key}가 필요하고, 같은 키로 다시 오면 {@code
 * Idempotent-Replayed: true}를 붙인다(06 4-0). 200과 {@code {roomRecordId, status, endRequested}}.
 */
@RestController
@RequiredArgsConstructor
public class RoomEndController {

  private final RoomEndService ends;

  /** 세입자 측 [이사했어요]는 거주 종료, 집주인 측 [이사함]은 거주 종료 요청(ROOM-9a·9b). */
  @PostMapping("/api/rooms/{id}/move-out")
  public ResponseEntity<MoveOutResult> moveOut(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return respond(ends.moveOut(id, memberId, idempotencyKey));
  }

  /** 거주 종료 요청에 "맞아요"(세입자 측). */
  @PostMapping("/api/rooms/{id}/move-out/confirm")
  public ResponseEntity<MoveOutResult> confirm(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return respond(ends.confirm(id, memberId, idempotencyKey));
  }

  /** 거주 종료 요청에 "아직 살고 있어요"(세입자 측, 사유 필수). */
  @PostMapping("/api/rooms/{id}/move-out/reject")
  public ResponseEntity<MoveOutResult> reject(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody(required = false) ReasonRequest request) {
    return respond(
        ends.reject(id, memberId, idempotencyKey, request == null ? null : request.reason()));
  }

  private static ResponseEntity<MoveOutResult> respond(MoveOutResult.Result result) {
    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
    if (result.replayed()) {
      ok.header(RoomPartController.REPLAYED, "true");
    }
    return ok.body(result.body());
  }
}
