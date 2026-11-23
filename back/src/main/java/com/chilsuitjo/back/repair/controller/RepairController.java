package com.chilsuitjo.back.repair.controller;

import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.repair.dto.CreatedResponse;
import com.chilsuitjo.back.repair.dto.RegisterRepairRequest;
import com.chilsuitjo.back.repair.dto.RepairCaseCreated;
import com.chilsuitjo.back.repair.service.RepairService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

  /**
   * 하자 등록(REP-1). 처음 성공과 다시 온 요청 모두 201과 만든 ID를 돌려준다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를
   * 붙인다(06 4-0). {@code Idempotency-Key}가 없으면 400이다.
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
}
