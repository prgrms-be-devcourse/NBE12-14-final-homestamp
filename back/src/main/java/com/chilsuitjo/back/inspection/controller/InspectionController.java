package com.chilsuitjo.back.inspection.controller;

import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.inspection.dto.AddInspectionItemRequest;
import com.chilsuitjo.back.inspection.dto.InspectionCreated;
import com.chilsuitjo.back.inspection.dto.InspectionDetail;
import com.chilsuitjo.back.inspection.dto.InspectionStatusChanged;
import com.chilsuitjo.back.inspection.dto.InspectionStatusResponse;
import com.chilsuitjo.back.inspection.dto.StartInspectionRequest;
import com.chilsuitjo.back.inspection.service.InspectionQueryService;
import com.chilsuitjo.back.inspection.service.InspectionService;
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

/** 점검 API(06 4-7). */
@RestController
@RequiredArgsConstructor
public class InspectionController {

  /** 같은 키로 다시 온 요청에 붙이는 머리(06 4-0). */
  static final String REPLAYED = "Idempotent-Replayed";

  private final InspectionService inspections;
  private final InspectionQueryService queries;

  /**
   * 점검 시작(INSP-1a·2b). 처음 성공과 다시 온 요청 모두 201과 만든 점검 ID를 돌려준다. 다시 온 요청이면 {@code Idempotent-Replayed:
   * true}를 붙인다(06 4-0).
   *
   * <p><b>현재 구현 범위의 한계</b>(06과 다른 점): 퇴거 점검({@code MOVE_OUT}, INSP-3)은 아직 시작할 수 없고({@code
   * INVALID_STATE}), 늦은 입주 표시({@code late}, INSP-2a)는 채우지 않는다. 둘 다 B가 만든다(07 WBS 3주차).
   */
  @PostMapping("/api/rooms/{roomId}/inspections")
  public ResponseEntity<CreatedResponse> start(
      @PathVariable UUID roomId,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody StartInspectionRequest request) {
    return created(inspections.start(roomId, memberId, idempotencyKey, request));
  }

  /**
   * 점검 상세(S-14). 작성 중이면 기준 측이 아닌 사람에게는 "작성 중"만 보인다(INSP-1a). 버튼은 {@code availableActions}에 있는 것만 보여
   * 준다(06 4-0).
   */
  @GetMapping("/api/inspections/{id}")
  public InspectionDetail detail(@PathVariable UUID id, @LoginMember UUID memberId) {
    return queries.detail(id, memberId);
  }

  /**
   * 점검 항목 더하기. 처음 성공과 다시 온 요청 모두 201과 만든 항목 ID다.
   *
   * <p><b>현재 구현 범위의 한계</b>(06과 다른 점): 지금은 작성 중 기준 측만 더할 수 있다. 규칙으로는 상대도 작성 중에 더할 수 있다(03 6-6 2번).
   * 상대가 더하기·잠긴 뒤 추가(INSP-1d·1e)와 그 방향 확인 요청은 후속 작업에서 만든다.
   */
  @PostMapping("/api/inspections/{id}/items")
  public ResponseEntity<CreatedResponse> addItem(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody AddInspectionItemRequest request) {
    return created(inspections.addItem(id, memberId, idempotencyKey, request));
  }

  /**
   * 확인 요청(INSP-1f). 처음 성공과 다시 온 요청 모두 200과 점검의 상태다. 다시 온 요청이면 {@code Idempotent-Replayed: true}를
   * 붙인다(06 4-0). 상대 측은 작성 중이 {@code inspection.draft.stale-after}를 넘은 뒤에만 보낼 수 있다.
   *
   * <p><b>잠정(D 확인 전, #218 ③)</b>: 상대가 연결돼 있지 않으면 상태만 바꾸고 확인 요청은 열지 않는다.
   */
  @PostMapping("/api/inspections/{id}/request-confirm")
  public ResponseEntity<InspectionStatusResponse> requestConfirm(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    InspectionStatusChanged changed = inspections.requestConfirm(id, memberId, idempotencyKey);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (changed.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new InspectionStatusResponse(changed.id(), changed.status()));
  }

  private static ResponseEntity<CreatedResponse> created(InspectionCreated created) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new CreatedResponse(created.id()));
  }
}
