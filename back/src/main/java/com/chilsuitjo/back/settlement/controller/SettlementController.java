package com.chilsuitjo.back.settlement.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.ReasonRequest;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.settlement.dto.FinishConfirmRequest;
import com.chilsuitjo.back.settlement.dto.FinishResult;
import com.chilsuitjo.back.settlement.dto.SettlementCreated;
import com.chilsuitjo.back.settlement.dto.SettlementDetail;
import com.chilsuitjo.back.settlement.dto.TransferRequest;
import com.chilsuitjo.back.settlement.service.SettlementQueryService;
import com.chilsuitjo.back.settlement.service.SettlementService;
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

/** 정산 API(06 4-8). */
@RestController
@RequiredArgsConstructor
public class SettlementController {

  /** 같은 키로 다시 온 요청에 붙이는 머리(06 4-0). */
  static final String REPLAYED = "Idempotent-Replayed";

  private final SettlementService settlements;
  private final SettlementQueryService queries;

  /**
   * 정산 열기(SET-1). 처음 성공과 다시 온 요청 모두 201과 만든 정산 ID를 돌려준다. 다시 온 요청이면 {@code Idempotent-Replayed:
   * true}를 붙인다(06 4-0). 본문은 없다.
   */
  @PostMapping("/api/rooms/{roomId}/settlement")
  public ResponseEntity<CreatedResponse> open(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return created(settlements.open(roomId, memberId, idempotencyKey));
  }

  /** 송금 기록(SET-4). 보내는 측이 보내면 보낸 기록, 받는 측이 보내면 [받았어요(혼자)]다. 처음 성공과 다시 온 요청 모두 201과 만든 송금 ID다. */
  @PostMapping("/api/settlements/{id}/transfers")
  public ResponseEntity<CreatedResponse> recordTransfer(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody TransferRequest request) {
    return created(settlements.recordTransfer(id, memberId, idempotencyKey, request));
  }

  /** 송금 응답(SET-4). 그 기록을 남기지 않은 측만 답한다. 처음 성공과 다시 온 요청 모두 200과 {@code AnswerResult}다. */
  @PostMapping("/api/settlement-transfers/{id}/respond")
  public ResponseEntity<AnswerResult> respondTransfer(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = settlements.respondTransfer(id, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(result);
  }

  /** 정산 끝내기 제안(SET-5a). 본문 없음. 처음 성공과 다시 온 요청 모두 200과 {@code FinishResult}다. */
  @PostMapping("/api/settlements/{id}/finish")
  public ResponseEntity<FinishResult> proposeFinish(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return finished(settlements.proposeFinish(id, memberId, idempotencyKey));
  }

  /** 정산 끝내기 확인(SET-5a·5e) → 정산 완료. 본문은 본 정산 모습 번호 {@code {seenVersionNo}}다. */
  @PostMapping("/api/settlements/{id}/finish/confirm")
  public ResponseEntity<FinishResult> confirmFinish(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody FinishConfirmRequest request) {
    return finished(settlements.confirmFinish(id, memberId, idempotencyKey, request));
  }

  /** [아직 안 끝났어요](SET-5c). 본문은 {@code {reason}}이다. */
  @PostMapping("/api/settlements/{id}/finish/not-yet")
  public ResponseEntity<FinishResult> notYetFinish(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ReasonRequest request) {
    return finished(settlements.notYetFinish(id, memberId, idempotencyKey, request));
  }

  /** 혼자 끝내기(SET-5d) → 정산 완료. 본문 없음. */
  @PostMapping("/api/settlements/{id}/finish/alone")
  public ResponseEntity<FinishResult> finishAlone(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return finished(settlements.finishAlone(id, memberId, idempotencyKey));
  }

  private static ResponseEntity<FinishResult> finished(FinishResult result) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(result);
  }

  private static ResponseEntity<CreatedResponse> created(SettlementCreated created) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new CreatedResponse(created.id()));
  }

  /** 호실의 정산(S-15). 아직 열지 않았으면 {@code NOT_FOUND}다. */
  @GetMapping("/api/rooms/{roomId}/settlement")
  public SettlementDetail ofRoom(@PathVariable UUID roomId, @LoginMember UUID memberId) {
    return queries.ofRoom(roomId, memberId);
  }

  /** 정산 화면(S-15). 버튼은 {@code availableActions}에 있는 것만 보여 준다(06 4-0). */
  @GetMapping("/api/settlements/{id}")
  public SettlementDetail detail(@PathVariable UUID id, @LoginMember UUID memberId) {
    return queries.detail(id, memberId);
  }
}
