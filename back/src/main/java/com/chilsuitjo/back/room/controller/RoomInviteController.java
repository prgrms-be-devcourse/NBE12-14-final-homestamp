package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.CounterpartPhoneRequest;
import com.chilsuitjo.back.room.dto.InviteRequest;
import com.chilsuitjo.back.room.dto.Invited;
import com.chilsuitjo.back.room.service.InviteService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상대 초대 API(06 4-3, ROOM-4, CONS-2). {@code Idempotency-Key}가 필요하고, 같은 키로 다시 오면 {@code
 * Idempotent-Replayed: true}를 붙인다(06 4-0).
 */
@RestController
@RequiredArgsConstructor
public class RoomInviteController {

  private final InviteService invites;

  /** 상대 초대. 200과 초대한 자리 {@code {partyId, status}}. */
  @PostMapping("/api/rooms/{roomId}/invite")
  public ResponseEntity<Invited> invite(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody InviteRequest request) {
    return respond(invites.invite(roomId, memberId, idempotencyKey, request));
  }

  /** 상대 번호 고쳐 다시 초대(ROOM-4, LINK-4, #437). 200과 다시 초대한 자리 {@code {partyId, status}}. */
  @PostMapping("/api/rooms/{roomId}/counterpart-phone")
  public ResponseEntity<Invited> changeCounterpartPhone(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CounterpartPhoneRequest request) {
    return respond(invites.changePhone(roomId, memberId, idempotencyKey, request));
  }

  private static ResponseEntity<Invited> respond(Invited.Result result) {
    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
    if (result.replayed()) {
      ok.header(RoomPartController.REPLAYED, "true");
    }
    return ok.body(result.invited());
  }
}
