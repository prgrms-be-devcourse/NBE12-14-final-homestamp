package com.chilsuitjo.back.room.service;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.room.dto.RoomEndView;
import com.chilsuitjo.back.room.repository.RoomEndStateStore;
import com.chilsuitjo.back.room.rule.RoomState;
import com.chilsuitjo.back.room.rule.RoomTransitions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 호실 상세의 거주 종료 행동과 칸(06 4-3, ROOM-9).
 *
 * <p>행동 이름은 API 끝 단어다(06 4-0): {@code move-out}(세입자 [이사했어요]·집주인 [이사함]), {@code confirm}·{@code
 * reject}(세입자가 거주 종료 요청에 답함). 실제 요청과 같은 판단으로 만든다: 호실 단위 거절은 {@code RoomGate.rejection}, 규칙은 {@link
 * RoomTransitions}의 같은 함수, 상태는 {@link RoomEndStateStore#read}.
 *
 * <p><b>활성화 조건</b>(#421 D 리뷰, #445): 거주 종료 요청 문자·기다림 시작, 미확인 뒤 적용, 양쪽 거주 종료 안내가 연결되기 전에는 세입자가 요청을 알
 * 길이 없다. 그래서 {@code app.room.end.actions-enabled}가 꺼져 있으면(기본) 행동을 넣지 않는다. API 자체는 그대로다.
 */
@Component
public class RoomEndActions {

  private final RoomEndStateStore store;
  private final WaitQuery waitQuery;
  private final boolean enabled;

  public RoomEndActions(
      RoomEndStateStore store,
      WaitQuery waitQuery,
      @Value("${app.room.end.actions-enabled:false}") boolean enabled) {
    this.store = store;
    this.waitQuery = waitQuery;
    this.enabled = enabled;
  }

  /** 이 측이 지금 누를 수 있는 거주 종료 행동 이름. 설정이 꺼져 있으면 빈 목록이다. */
  public List<String> actions(UUID roomRecordId, PartySide side, RoomGate gate) {
    if (!enabled) {
      return List.of();
    }
    RoomState state = store.read(roomRecordId, side);
    List<String> names = new ArrayList<>();
    boolean now = state.movesOutNow();
    ActionType moveOut = now ? ActionType.MOVE_OUT : ActionType.REQUEST_END;
    boolean canMoveOut =
        now ? RoomTransitions.canMoveOut(state, side) : RoomTransitions.canRequestEnd(state, side);
    if (canMoveOut && gate.rejection(moveOut).isEmpty()) {
      names.add("move-out");
    }
    if (RoomTransitions.canAnswerEnd(state, side)) {
      if (gate.rejection(ActionType.CONFIRM_END).isEmpty()) {
        names.add("confirm");
      }
      if (gate.rejection(ActionType.REJECT_END).isEmpty()) {
        names.add("reject");
      }
    }
    return names;
  }

  /**
   * 집주인 측이 연 가장 최근 거주 종료 요청. 없으면 null. 공유를 그만둔 뒤에도 그대로 준다(클래스 {@link RoomEndView} 설명).
   *
   * @param roomActions 이 호실의 행동 기록({@code ActionReader.findByRoom}, {@code room_seq} 순). 세입자가 답한
   *     요청일 때만 읽는다
   */
  public RoomEndView.EndRequest endRequest(
      UUID roomRecordId, Supplier<List<ActionEntry>> roomActions) {
    return waitQuery
        .latest(RequestKind.END_REQUEST, RequestTarget.roomRecord(roomRecordId))
        .filter(view -> view.requestedBySide() == PartySide.LANDLORD)
        .map(view ->
            new RoomEndView.EndRequest(
                view.status(),
                view.openedAt(),
                view.dueAt(),
                view.status() == RequestStatus.ANSWERED
                    ? rejectReason(roomActions.get(), view.openedAt())
                    : null))
        .orElse(null);
  }

  /**
   * 이 요청을 연 뒤 남은 마지막 "아직 살고 있어요"의 사유. "맞아요"로 답했으면 없다. 앞 요청의 거절은 이 요청을 열기 전이라 들어오지 않는다(요청은 한 번에 하나만
   * 열리고, 거절이 그 요청을 닫는다).
   */
  private static String rejectReason(List<ActionEntry> roomActions, Instant openedAt) {
    return roomActions.stream()
        .filter(entry -> entry.type() == ActionType.REJECT_END)
        .filter(entry -> !entry.createdAt().isBefore(openedAt))
        .max(Comparator.comparing(ActionEntry::createdAt))
        .map(ActionEntry::reason)
        .orElse(null);
  }

  /**
   * 집주인 측이 혼자 남긴 거주 종료의 이유({@code MOVE_OUT} payload {@code solo_reason}). 세입자 측 [이사했어요]·"맞아요"로 끝났거나
   * 아직 거주 중이면 null. 거주 종료를 되돌리는 전이가 없어 호실의 {@code MOVE_OUT}은 많아야 하나다({@code RoomTransitions}).
   */
  public static String endSoloReason(List<ActionEntry> roomActions) {
    return roomActions.stream()
        .filter(entry -> entry.type() == ActionType.MOVE_OUT)
        .reduce((first, second) -> second)
        .map(entry -> entry.payload().get(RoomEndStateStore.SOLO_REASON))
        .map(Object::toString)
        .orElse(null);
  }
}
