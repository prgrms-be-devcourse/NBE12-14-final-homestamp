package com.chilsuitjo.back.payment.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.payment.service.PaymentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 지불 API(06 4-7). 지불 기록은 수리 건 아래 경로라 {@code RepairController}에 있다. */
@RestController
@RequiredArgsConstructor
public class PaymentController {

  /** 같은 키로 다시 온 요청에 붙이는 머리(06 4-0). */
  static final String REPLAYED = "Idempotent-Replayed";

  private final PaymentService payments;

  /**
   * 금액 응답(MONEY-2). 맞아요·더 필요해요(보류)·사실과 달라요(이견). 처음 성공과 다시 온 요청 모두 200과 {@code AnswerResult}({@code
   * targetId} = 지불, {@code versionNo} = 본 판, {@code state} = 지불의 응답)다.
   */
  @PostMapping("/api/payments/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = payments.respond(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(result);
  }
}
