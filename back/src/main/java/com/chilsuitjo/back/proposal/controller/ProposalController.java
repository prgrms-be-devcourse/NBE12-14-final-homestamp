package com.chilsuitjo.back.proposal.controller;

import com.chilsuitjo.back.global.common.dto.ReasonRequest;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.proposal.service.ProposalService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proposals")
@RequiredArgsConstructor
public class ProposalController {

  private final ProposalService proposalService;
  static final String REPLAYED = "Idempotent-Replayed";

  @PostMapping("/{id}/agree")
  public ResponseEntity<ProposalResult> agree(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    ProposalResult result = proposalService.agree(id, memberId, idempotencyKey);
    return reply(result);
  }

  /** 상대 측의 이견. 사유는 필수다({@code REASON_REQUIRED}, decisions 2026-11-19). */
  @PostMapping("/{id}/dispute")
  public ResponseEntity<ProposalResult> dispute(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ReasonRequest request) {
    ProposalResult result =
        proposalService.dispute(
            id, memberId, idempotencyKey, request == null ? null : request.reason());
    return reply(result);
  }

  /** 처음 성공과 다시 온 요청 모두 200이다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를 붙인다(06 4-0). */
  private static ResponseEntity<ProposalResult> reply(ProposalResult result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK);
    if (result.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(result);
  }
}
