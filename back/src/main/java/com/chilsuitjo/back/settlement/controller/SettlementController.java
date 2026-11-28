package com.chilsuitjo.back.settlement.controller;

import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.settlement.dto.SettlementCreated;
import com.chilsuitjo.back.settlement.dto.SettlementDetail;
import com.chilsuitjo.back.settlement.service.SettlementQueryService;
import com.chilsuitjo.back.settlement.service.SettlementService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    SettlementCreated created = settlements.open(roomId, memberId, idempotencyKey);
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
