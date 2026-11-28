package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.room.dto.MoveOutResult;
import com.chilsuitjo.back.room.repository.RoomEndStateStore;
import com.chilsuitjo.back.room.repository.RoomRecordRepository;
import com.chilsuitjo.back.room.rule.RoomState;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.rule.RoomTransitions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 거주 종료(06 ROOM-9, 4-3 {@code move-out}·{@code move-out/confirm}·{@code move-out/reject}). 트랜잭션은
 * pipeline이 연다(09 3-1). 그래서 여기에 {@code @Transactional}을 두지 않는다.
 *
 * <p>[이사했어요]·[이사함]은 한 주소다. 행동하는 측과 상대 자리는 pipeline이 호실을 잠근 뒤에야 알 수 있어서, 두 행동을 {@code when}으로 건너뛸 수
 * 있는 단계로 두고 잠근 뒤 읽은 상태로 하나만 남긴다: 바로 거주 종료면 {@code MOVE_OUT}, 아니면 거주 종료 요청 {@code REQUEST_END}.
 *
 * <p>오류: 남의 호실 {@code NOT_FOUND}, 정산 완료·합류 전 기록이면 pipeline 거절, 거주 중이 아니거나 이미 요청을 보냈거나 답할 요청이 없거나
 * 세입자가 답할 수 없는 자리(연결 전·거절·탈퇴, D 확인 전, #415)면 {@code INVALID_STATE}, 사유 없는 "아직 살고 있어요"는 {@code
 * REASON_REQUIRED}.
 */
@Service
@RequiredArgsConstructor
public class RoomEndService {

  private final ActionPipeline pipeline;
  private final RoomEndStateStore store;
  private final RoomTransitions rules;
  private final RoomRecordRepository rooms;
  private final WaitQuery waitQuery;

  /** 세입자 측 [이사했어요](바로 거주 종료), 집주인 측 [이사함](거주 종료 요청, 물을 세입자가 없으면 바로 거주 종료). */
  public MoveOutResult.Result moveOut(UUID roomRecordId, UUID memberId, String idempotencyKey) {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .step(
                    ActionStep.of(ActionType.MOVE_OUT, done -> null, store, rules)
                        .when(RoomState::movesOutNow))
                .step(
                    ActionStep.of(ActionType.REQUEST_END, done -> null, store, rules)
                        .when(state -> !state.movesOutNow())));
    return result(roomRecordId, result);
  }

  /** 세입자 측이 거주 종료 요청에 "맞아요". */
  public MoveOutResult.Result confirm(UUID roomRecordId, UUID memberId, String idempotencyKey) {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .step(ActionType.CONFIRM_END, done -> null, store, rules));
    return result(roomRecordId, result);
  }

  /** 세입자 측이 거주 종료 요청에 "아직 살고 있어요"(사유 필수). 사유는 입력 검사라 잠금보다 먼저 본다. */
  public MoveOutResult.Result reject(
      UUID roomRecordId, UUID memberId, String idempotencyKey, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
                .step(
                    ActionStep.of(ActionType.REJECT_END, done -> null, store, rules)
                        .withReason(reason.strip())));
    return result(roomRecordId, result);
  }

  private MoveOutResult.Result result(UUID roomRecordId, BatchResult result) {
    RoomStatus status = rooms.findById(roomRecordId).orElseThrow().getStatus();
    boolean endRequested =
        waitQuery
            .latest(RequestKind.END_REQUEST, RequestTarget.roomRecord(roomRecordId))
            .filter(view -> view.requestedBySide() == PartySide.LANDLORD)
            .filter(view ->
                view.status() == RequestStatus.WAITING
                    || view.status() == RequestStatus.UNCONFIRMED)
            .isPresent();
    return new MoveOutResult.Result(
        new MoveOutResult(roomRecordId, status, endRequested), result.replayed());
  }
}
