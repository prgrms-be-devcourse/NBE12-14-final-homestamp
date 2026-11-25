package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.notification.dto.InfoCommand;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.notification.service.InAppNotifier;
import com.chilsuitjo.back.notification.service.WaitService;
import com.chilsuitjo.back.room.service.RoomMoveInDates;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 점검 상태 행을 읽고 쓴다({@code StateStore}, 09 2장 {@code repository/}). 규칙은 검사하지 않는다(규칙은 {@code
 * InspectionTransitions}에만).
 */
@Component
@RequiredArgsConstructor
public class InspectionStateStore {

  /** 점검 시작 payload의 종류 키({@code StartInspectionPayload.kind}). */
  public static final String KIND = "kind";

  private final InspectionRepository inspections;
  private final RoomMoveInDates moveInDates;
  private final CounterpartReader parties;
  private final ActionRequestContext requestContext;
  private final WaitService waits;
  private final InAppNotifier notifier;

  /** 점검 시작(06 INSP-1a·2b)의 저장 방법. 종류와 채울 입주일은 payload에 있다. */
  public StateStore<InspectionState> starting() {
    return new Starting();
  }

  /** 확인 요청(06 4-7 {@code request-confirm}, INSP-1f)의 저장 방법. */
  public StateStore<InspectionState> requesting() {
    return new Requesting();
  }

  /**
   * 지금 상태. 같은 키로 다시 온 요청이면 처음 만든 점검을 읽는다. 처음 요청이면 그 호실의 같은 종류 점검을 찾고(있으면 {@code apply}가 {@code
   * INSPECTION_EXISTS}로 막는다), 없으면 호실 사실(입주일이 비었나, 거주 종료 뒤인가)만 담는다. 호실을 잠근 뒤라 동시에 시작해도 뒤에 온 쪽은 먼저 만든
   * 점검을 본다(03 1-3).
   */
  InspectionState load(ActionCommand command) {
    ActionTarget target = command.target();
    if (target != null) {
      if (target.kind() != TargetKind.INSPECTION) {
        throw new IllegalArgumentException("점검이 아닌 대상이에요: " + target);
      }
      return current(row(command));
    }
    InspectionKind kind = kindOf(command);
    return inspections
        .findByRoomRecordIdAndKind(command.roomRecordId(), kind)
        .map(this::current)
        .orElseGet(() -> notStarted(command));
  }

  /**
   * 이 점검 행의 지금 상태({@link #load}와 같은 값). 상세 조회가 {@code availableActions}를 계산할 때도 쓴다(06 4-0 "서버의 같은
   * 함수"). 지금 호실 단계에서 점검 확인 요청을 열 수 있는지를 함께 담는다(03 K).
   */
  public InspectionState current(Inspection row) {
    RoomParties room = parties.read(row.getRoomRecordId(), row.getAuthorSide());
    boolean canOpen =
        room.canOpen(RequestKind.INSPECTION_CONFIRM)
            && waits.canOpen(row.getRoomRecordId(), RequestKind.INSPECTION_CONFIRM);
    return new InspectionState(
        row.getKind(),
        row.getStatus(),
        row.getAuthorSide(),
        row.getCreatedAt(),
        null,
        null,
        canOpen);
  }

  private InspectionState notStarted(ActionCommand command) {
    boolean dateMissing = moveInDates.find(command.roomRecordId()).isEmpty();
    RoomPhase phase =
        parties
            .read(command.roomRecordId(), Objects.requireNonNull(command.actor().side(), "side"))
            .phase();
    return InspectionState.notStarted(dateMissing, phase != RoomPhase.LIVING);
  }

  private Inspection row(ActionCommand command) {
    return inspections
        .findByIdAndRoomRecordId(command.target().id(), command.roomRecordId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static InspectionKind kindOf(ActionCommand command) {
    Object kind = command.payload().get(KIND);
    if (kind == null) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return InspectionKind.valueOf(kind.toString());
  }

  /** 공유 중단 제한이 살아 있나(불변식 18). 어느 쪽이 그만뒀든 같고, 상대가 탈퇴해 풀린 공유 중단은 보지 않는다({@code RoomParties}의 판단). */
  private static boolean sharingStopped(RoomParties room) {
    return room.mySharingStoppedApplies() || room.counterpartSharingStoppedApplies();
  }

  private final class Starting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 작성 중으로 만든다. 입주일이 비어 있던 호실이면 같은 트랜잭션에서 입주일을 채운다(06 4-7). 채운 입주일은 이미 행동 payload에 있어 그 기록이
     * 원본이다({@code RoomMoveInDates}). 작성 중이라 상대에게 아직 보이지 않는다({@code first_shown_at} 없음, 06 INSP-1a).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      Object date = command.payload().get(RoomMoveInDates.FILLED_PAYLOAD_KEY);
      if (date != null) {
        moveInDates.fill(command.roomRecordId(), LocalDate.parse(date.toString()));
      }
      Inspection row =
          inspections.save(
              new Inspection(
                  command.roomRecordId(), next.kind(), next.authorSide(), requestContext.at()));
      return SavedState.created(ActionTarget.inspection(row.getId()));
    }
  }

  private final class Requesting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 확인 요청으로 바꾼다. 상대(기준 측의 반대)가 지금 이 점검을 볼 수 있을 때만, 같은 트랜잭션에서 점검 확인 요청을 열고({@code
     * INSPECTION_CONFIRM}, 요청한 측 = 기준 측) {@code first_shown_at}을 행동 시각으로 채운다(decisions 2026-11-23과
     * 같은 기준).
     *
     * <ul>
     *   <li>공유 중단 제한이 살아 있으면 요청을 열지 않는다(03 B "그만둔 사람에게는 새 확인 요청을 만들지 않는다", 불변식 18). 다시 공유하면 그때 요청이
     *       생긴다(03 1-4 "다시 공유한 뒤 새 확인 요청")
     *   <li><b>잠정(D 확인 전, #218 ③)</b>: 상대가 연결돼 있지 않으면(상대 없음·연결 전) 요청을 열지 않는다. 연결 전 상대의 링크 알림(03
     *       1-4)은 #163·링크 발급 뒤, 혼자 잠금은 잠금 작업에서 정한다
     * </ul>
     *
     * <p>상대 측이 "지금까지 쓴 것으로" 보냈으면 기준 측에 서비스 안 알림을 보낸다(06 4-7). 공유 중단 제한이 살아 있으면 그 뒤의 상대 행동이 보이지 않아야
     * 하므로 보내지 않는다(불변식 18). 알림 주제는 #116에서 정한다. 이 행동은 판을 보지 않아 내용 지문이 없다(안내서 3-3).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      Inspection row = row(command);
      row.changeStatus(next.status());
      PartySide author = row.getAuthorSide();
      RoomParties room = parties.read(command.roomRecordId(), author);
      Instant at = requestContext.at();
      RequestTarget target = RequestTarget.of(TargetKind.INSPECTION, row.getId());
      boolean stopped = sharingStopped(room);
      if (!stopped && room.counterpart().presence() == PartyPresence.CONNECTED) {
        row.markShown(at);
        waits.open(
            new OpenRequestCommand(
                command.roomRecordId(), RequestKind.INSPECTION_CONFIRM, target, author));
      }
      if (command.actor().side() != author && !stopped && room.mine().memberId() != null) {
        notifier.notify(
            new InfoCommand(room.mine().memberId(), command.roomRecordId(), target, null));
      }
      return SavedState.unchanged();
    }
  }
}
