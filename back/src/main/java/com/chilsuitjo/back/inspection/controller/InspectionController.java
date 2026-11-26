package com.chilsuitjo.back.inspection.controller;

import com.chilsuitjo.back.global.common.dto.AnswerResult;
import com.chilsuitjo.back.global.common.dto.RespondRequest;
import com.chilsuitjo.back.global.common.dto.SupplementRequest;
import com.chilsuitjo.back.global.response.CreatedResponse;
import com.chilsuitjo.back.global.security.LoginMember;
import com.chilsuitjo.back.inspection.dto.AddInspectionItemRequest;
import com.chilsuitjo.back.inspection.dto.ConfirmAllRequest;
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
   * 붙인다(06 4-0). 상대 측은 점검 시작 뒤 {@code inspection.draft.stale-after}가 지난 때(이상)부터 보낼 수 있다.
   *
   * <p>상태는 상대 자리와 상관없이 바뀐다. 확인 요청은 상대가 연결됐거나 연결 전({@code INVITED})일 때만 열고, 상대 없음·거절·탈퇴·공유 중단이면 열지
   * 않는다(#163 6-1, {@code InspectionStateStore}).
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

  /**
   * 항목 응답(06 4-7). 맞아요·보류·이견. 그 항목을 더하지 않은 측만, 확인 요청 뒤에만 답한다. 처음 성공과 다시 온 요청 모두 200과 {@code
   * AnswerResult}다.
   */
  @PostMapping("/api/inspection-items/{id}/respond")
  public ResponseEntity<AnswerResult> respond(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RespondRequest request) {
    AnswerResult result = inspections.respond(id, memberId, idempotencyKey, request);
    return withReplayed(result.replayed()).body(result);
  }

  /**
   * 남은 항목 모두 확인(06 4-7). 본 미응답 항목과 판 번호를 모두 보내고, 지금과 다르면 {@code VERSION_CONFLICT}다. 항목이 없는 점검이면 빈
   * 목록으로 "하자 기록 없음"을 확인한다. 처음 성공과 다시 온 요청 모두 200과 점검의 상태다.
   */
  @PostMapping("/api/inspections/{id}/confirm-all")
  public ResponseEntity<InspectionStatusResponse> confirmAll(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ConfirmAllRequest request) {
    InspectionStatusChanged changed = inspections.confirmAll(id, memberId, idempotencyKey, request);
    return withReplayed(changed.replayed())
        .body(new InspectionStatusResponse(changed.id(), changed.status()));
  }

  /**
   * 보충하고 다시 확인 요청(06 4-7). 그 항목을 더한 측만, 보류·이견 뒤에, 7일에 한 번. 사진·설명 중 하나 이상이 필요하고 사진은 한 번에 10장까지 항목에
   * 붙는다. 처음 성공과 다시 온 요청 모두 200이다.
   */
  @PostMapping("/api/inspection-items/{id}/supplement")
  public ResponseEntity<AnswerResult> supplement(
      @PathVariable UUID id,
      @LoginMember UUID memberId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SupplementRequest request) {
    AnswerResult result = inspections.supplement(id, memberId, idempotencyKey, request);
    return withReplayed(result.replayed()).body(result);
  }

  private static ResponseEntity.BodyBuilder withReplayed(boolean replayed) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (replayed) {
      response.header(REPLAYED, "true");
    }
    return response;
  }

  private static ResponseEntity<CreatedResponse> created(InspectionCreated created) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (created.replayed()) {
      response.header(REPLAYED, "true");
    }
    return response.body(new CreatedResponse(created.id()));
  }
}
