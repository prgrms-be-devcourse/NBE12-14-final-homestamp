package com.chilsuitjo.back.repair.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.repair.dto.CauseNoteCreated;
import com.chilsuitjo.back.repair.dto.CauseNoteRequest;
import com.chilsuitjo.back.repair.service.CauseNoteService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 원인 설명 API(06 4-6, REP-8). 설명은 수리 건 상세({@code causeNotes})에서 읽는다. */
@RestController
@RequiredArgsConstructor
public class CauseNoteController {

  private final CauseNoteService causeNotes;

  /**
   * 원인 설명 남기기·고치기. 처음 성공과 다시 온 요청 모두 201과 새 설명 ID다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를
   * 붙인다(06 4-0). 고치려면 내 지금 설명을 {@code previousId}로 보낸다.
   */
  @PostMapping("/api/repair-cases/{id}/cause-notes")
  public ResponseEntity<CreatedResponse> note(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody CauseNoteRequest request) {
    CauseNoteCreated created = causeNotes.note(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header(RepairController.REPLAYED, "true");
    }
    return response.body(new CreatedResponse(created.id()));
  }

  /**
   * 원인 설명에 응답. 맞아요·더 필요해요(보류)·사실과 달라요(이견). 처음 성공과 다시 온 요청 모두 200과 {@code AnswerResult}({@code
   * targetId} = 설명, {@code versionNo}는 늘 null)다.
   */
  @PostMapping("/api/cause-notes/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = causeNotes.respond(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(RepairController.REPLAYED, "true");
    }
    return response.body(result);
  }
}
