package com.chilsuitjo.back.correction.controller;

import com.chilsuitjo.back.correction.dto.AcceptAllRequest;
import com.chilsuitjo.back.correction.dto.AcceptAllResponse;
import com.chilsuitjo.back.correction.dto.CorrectRequest;
import com.chilsuitjo.back.correction.dto.CorrectResponse;
import com.chilsuitjo.back.correction.dto.ResubmitResponse;
import com.chilsuitjo.back.correction.service.CorrectionService;
import com.chilsuitjo.back.correction.service.CorrectionService.BatchAnswered;
import com.chilsuitjo.back.correction.service.CorrectionService.Corrected;
import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
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

  /** [모두 확인]. 한 기록의 확인 전 고친 내용을 모두 맞아요로 답한다. 200과 확인한 고친 내용 ID들. */
  @PostMapping("/api/corrections/accept-all")
  public ResponseEntity<AcceptAllResponse> acceptAll(
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody AcceptAllRequest request) {
    BatchAnswered answered = corrections.acceptAll(memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (answered.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(answered.response());
  }

  /** [사진 더해서 다시 요청]. 같은 값에 근거(사진·설명)를 더해 새 고친 내용으로 다시 보낸다. 200과 새 고친 내용 ID. */
  @PostMapping("/api/corrections/{id}/resubmit")
  public ResponseEntity<ResubmitResponse> resubmit(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody(required = false) SupplementRequest request) {
    CorrectionService.Resubmitted resubmitted =
        corrections.resubmit(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (resubmitted.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new ResubmitResponse(resubmitted.correctionId()));
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
