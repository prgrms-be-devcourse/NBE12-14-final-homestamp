package com.chilsuitjo.back.repair.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.repair.service.SituationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상황 변화 응답 API(06 4-6, REP-6d). [상황이 바뀌었어요]를 켜는 쪽은 진행 상황 남기기({@code POST
 * /api/repair-cases/{id}/progress-notes}의 {@code asksCheck})다.
 */
@RestController
@RequiredArgsConstructor
public class SituationController {

  private final SituationService situations;

  /**
   * 상황 변화에 응답. 맞아요·더 필요해요(보류)·사실과 달라요(이견). 처음 성공과 다시 온 요청 모두 200과 {@code AnswerResult}({@code
   * targetId} = 진행 상황, {@code versionNo}는 늘 null)다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를
   * 붙인다(06 4-0).
   *
   * <p><b>아직 06과 다르다</b>: 알리기(문자)는 만들지 않아 진행 상황을 남긴 측에게 알림이 가지 않는다(#134).
   */
  @PostMapping("/api/progress-notes/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = situations.respond(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(RepairController.REPLAYED, "true");
    }
    return response.body(result);
  }
}
