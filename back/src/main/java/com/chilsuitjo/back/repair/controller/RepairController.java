package com.chilsuitjo.back.repair.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.proposal.dto.ProposalRequest;
import com.chilsuitjo.back.proposal.dto.ProposalResult;
import com.chilsuitjo.back.repair.dto.DefectAckChanged;
import com.chilsuitjo.back.repair.dto.DefectAckResponse;
import com.chilsuitjo.back.repair.dto.RegisterRepairRequest;
import com.chilsuitjo.back.repair.dto.RepairCaseCreated;
import com.chilsuitjo.back.repair.dto.RepairCaseDetail;
import com.chilsuitjo.back.repair.service.RepairQueryService;
import com.chilsuitjo.back.repair.service.RepairService;
import jakarta.validation.Valid;
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

/** 수리 건 API(06 4-6). */
@RestController
@RequiredArgsConstructor
public class RepairController {

  /** 같은 키로 다시 온 요청에 붙이는 머리(06 4-0). */
  static final String REPLAYED = "Idempotent-Replayed";

  private final RepairService repairs;
  private final RepairQueryService queries;

  /**
   * 하자 등록(REP-1). 처음 성공과 다시 온 요청 모두 201과 만든 ID를 돌려준다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를
   * 붙인다(06 4-0). {@code Idempotency-Key}가 없으면 400이다.
   *
   * <p>사진은 업로드 표 1~10개가 필수다({@code PHOTO_REQUIRED}, #167). 실제 업로드 검사(#112) 전에는 가짜 사진 창구가 받는다.
   *
   * <p><b>아직 06과 다르다</b>: 알리기(응답 요청·문자)는 만들지 않아 상대에게 알림이 가지 않는다. 알리기 PR에서 06대로 맞춘다(#134).
   */
  @PostMapping("/api/rooms/{roomId}/repair-cases")
  public ResponseEntity<CreatedResponse> register(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RegisterRepairRequest request) {
    RepairCaseCreated created = repairs.register(roomId, memberId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new CreatedResponse(created.id()));
  }

  /**
   * 수리 건 상세(S-12). 버튼은 {@code availableActions}에 있는 것만 보여 준다(06 4-0).
   *
   * <p><b>아직 06과 다르다</b>: 사진·원인 설명·진행 상황·경과·같은 부위 입주 기록·통지 상태는 없고, {@code availableActions}에는 지금 있는
   * 행동({@code respond}·{@code supplement})만 나온다. 그 기능을 만드는 PR에서 칸을 더한다(#164).
   */
  @GetMapping("/api/repair-cases/{id}")
  public RepairCaseDetail detail(@PathVariable UUID id, @LoginMember UUID memberId) {
    return queries.detail(id, memberId);
  }

  /**
   * 하자 응답(REP-2·3·9). 맞아요·더 필요해요(보류)·사실과 달라요(이견). 처음 성공과 다시 온 요청 모두 200과 {@code AnswerResult}다. 다시
   * 온 요청이면 {@code Idempotent-Replayed: true}를 붙인다(06 4-0).
   */
  @PostMapping("/api/repair-cases/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = repairs.respond(id, memberId, idempotencyKey, request);
    return withReplayed(ResponseEntity.ok(), result.replayed()).body(result);
  }

  /**
   * 보충하고 다시 확인 요청(REP-2e). 처음 성공과 다시 온 요청 모두 200이다. 사진·설명 중 하나 이상이 필요하고, 사진은 한 번에 10장까지 수리 건에
   * 붙는다(#167).
   */
  @PostMapping("/api/repair-cases/{id}/supplement")
  public ResponseEntity<DefectAckResponse> supplement(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SupplementRequest request) {
    DefectAckChanged changed = repairs.supplement(id, memberId, idempotencyKey, request);
    return withReplayed(ResponseEntity.ok(), changed.replayed())
        .body(new DefectAckResponse(changed.id(), changed.defectAck()));
  }

  /**
   * 수리비 부담 제안(MONEY-3). 처음 성공과 다시 온 요청 모두 201과 만든 제안 ID다. 다시 온 요청이면 {@code Idempotent-Replayed:
   * true}를 붙인다(06 4-0 "새 기록을 만드는 행동이면 201 + 만든 ID만"). 줄 목록 또는 비용 없음이고, 기다리는 안이 있으면 {@code
   * INVALID_STATE}다 (decisions 2026-11-26).
   */
  @PostMapping("/api/repair-cases/{id}/proposals")
  public ResponseEntity<CreatedResponse> propose(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ProposalRequest request) {
    ProposalResult result = repairs.propose(id, memberId, idempotencyKey, request);
    return withReplayed(ResponseEntity.status(HttpStatus.CREATED), result.replayed())
        .body(new CreatedResponse(result.proposalId()));
  }

  private static ResponseEntity.BodyBuilder withReplayed(
      ResponseEntity.BodyBuilder response, boolean replayed) {
    if (replayed) {
      response.header(REPLAYED, "true");
    }
    return response;
  }
}
