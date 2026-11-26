package com.chilsuitjo.back.room.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.room.dto.ConnectRequest;
import com.chilsuitjo.back.room.dto.InvitationAnswered;
import com.chilsuitjo.back.room.dto.InvitationSummary;
import com.chilsuitjo.back.room.service.InvitationQueryService;
import com.chilsuitjo.back.room.service.InvitationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 받은 초대(06 4-3, ROOM-5, S-08). 연결·거절은 {@code Idempotency-Key}가 필요하고, 같은 키로 다시 오면 {@code
 * Idempotent-Replayed: true}를 붙인다(06 4-0).
 */
@RestController
@RequiredArgsConstructor
public class InvitationController {

  private final InvitationQueryService invitations;
  private final InvitationService answers;

  /** 내 인증 번호로 받은 초대. 몇 건뿐이라 커서 없이 배열로 준다(06 4-0 "목록"의 예외). */
  @GetMapping("/api/invitations")
  public List<InvitationSummary> list(@LoginMember UUID memberId) {
    return invitations.list(memberId);
  }

  /** 연결([동의하고 연결하기]). 200과 {@code {roomRecordId, partyId, status}}. */
  @PostMapping("/api/invitations/{partyId}/connect")
  public ResponseEntity<InvitationAnswered> connect(
      @PathVariable UUID partyId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody(required = false) ConnectRequest request) {
    return respond(answers.connect(partyId, memberId, idempotencyKey, request));
  }

  /** "제 집이 아니에요". 200과 {@code {partyId, status}}(호실 기록 ID는 주지 않음). */
  @PostMapping("/api/invitations/{partyId}/reject")
  public ResponseEntity<InvitationAnswered> reject(
      @PathVariable UUID partyId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return respond(answers.reject(partyId, memberId, idempotencyKey));
  }

  private static ResponseEntity<InvitationAnswered> respond(InvitationAnswered.Result result) {
    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
    if (result.replayed()) {
      ok.header(RoomPartController.REPLAYED, "true");
    }
    return ok.body(result.answered());
  }
}
