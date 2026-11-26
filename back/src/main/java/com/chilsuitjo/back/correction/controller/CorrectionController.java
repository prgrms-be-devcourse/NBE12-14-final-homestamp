package com.chilsuitjo.back.correction.controller;

import com.chilsuitjo.back.correction.dto.CorrectRequest;
import com.chilsuitjo.back.correction.dto.CorrectResponse;
import com.chilsuitjo.back.correction.service.CorrectionService;
import com.chilsuitjo.back.correction.service.CorrectionService.Corrected;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.security.LoginMember;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고치기(06 4-6, REP-7). 다시 온 요청이면 처음과 같은 상태 코드·모양에 {@code Idempotent-Replayed: true}를 붙인다(06 4-0).
 */
@RestController
@RequiredArgsConstructor
public class CorrectionController {

  static final String REPLAYED = "Idempotent-Replayed";

  private final CorrectionService corrections;

  /** 고치기. 결과(바로 바뀜·확인 요청)는 서버가 정하고 200에 결과·고친 내용 ID·화면 문구를 준다. */
  @PostMapping("/api/corrections")
  public ResponseEntity<CorrectResponse> correct(
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CorrectRequest request) {
    Corrected corrected = corrections.correct(memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (corrected.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(corrected.response());
  }

  /** 고친 내용에 응답. 맞아요·더 필요해요(보류)·사실과 달라요(이견). 200과 {@code AnswerResult}({@code targetId} = 고친 내용). */
  @PostMapping("/api/corrections/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = corrections.respond(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(result);
  }

  /** 고친 내용 거두기. 204. */
  @PostMapping("/api/corrections/{id}/withdraw")
  public ResponseEntity<Void> withdraw(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    boolean replayed = corrections.withdraw(id, memberId, idempotencyKey);
    ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
    if (replayed) {
      response.header(REPLAYED, "true");
    }
    return response.build();
  }
}
