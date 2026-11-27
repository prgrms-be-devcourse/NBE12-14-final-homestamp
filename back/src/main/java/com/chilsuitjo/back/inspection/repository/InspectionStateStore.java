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
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RoomParties;
import com.chilsuitjo.back.notification.rule.PartyPresence;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
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
  private final InspectionPhotos photos;

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
        canOpen,
        null,
        row.isLate());
  }

  private InspectionState notStarted(ActionCommand command) {
    LocalDate moveInDate = moveInDates.find(command.roomRecordId()).orElse(null);
    RoomPhase phase =
        parties
            .read(command.roomRecordId(), Objects.requireNonNull(command.actor().side(), "side"))
            .phase();
    return InspectionState.notStarted(moveInDate, phase != RoomPhase.LIVING);
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

  private final class Starting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 작성 중으로 만든다. 입주일이 비어 있던 호실이면 같은 트랜잭션에서 입주일을 채운다(06 4-7). 채운 입주일은 이미 행동 payload에 있어 그 기록이
     * 원본이다({@code RoomMoveInDates}). 작성 중이라 상대에게 아직 보이지 않는다({@code first_shown_at} 없음, 06 INSP-1a).
     * 늦은 입주 표시는 규칙이 정한 값 그대로다(INSP-2a).
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
                  command.roomRecordId(),
                  next.kind(),
                  next.authorSide(),
                  Boolean.TRUE.equals(next.late()),
                  requestContext.at()));
      return SavedState.created(ActionTarget.inspection(row.getId()));
    }
  }

  private final class Requesting implements StateStore<InspectionState> {

    @Override
    public InspectionState load(ActionCommand command) {
      return InspectionStateStore.this.load(command);
    }

    /**
     * 점검을 확인 요청으로 바꾼다. 상태는 상대 자리와 상관없이 바뀌고, 같은 트랜잭션에서 점검 확인 요청({@code INSPECTION_CONFIRM}, 요청한 측 =
     * 기준 측)을 열지와 {@code first_shown_at}을 채울지는 상대 자리로 나눈다(#163 6-1 D 추천).
     *
     * <ul>
     *   <li>공유 중단 제한이 살아 있으면(어느 쪽이든) 요청을 열지 않는다(03 B "그만둔 사람에게는 새 확인 요청을 만들지 않는다", 불변식 18). 다시 공유하면
     *       그때 요청이 생긴다(03 1-4 "다시 공유한 뒤 새 확인 요청")
     *   <li>연결됨: 요청을 열고 {@code first_shown_at}을 행동 시각으로 채운다(05 2-10, decisions 2026-11-23과 같은 기준)
     *   <li>연결 전({@code INVITED}): 요청을 연다(03 1-4 "연결 전 상대" ① "링크로 알리고 기다린다", 06 ROOM-4c). 점검이 아직
     *       상대에게 보이지 않아 {@code first_shown_at}은 비워 두고 연결할 때 채운다. 기다림은 요청을 만든 시각이 아니라 첫 통지가 전달된 시각부터
     *       센다(05 {@code first_notified_at}, D 담당)
     *   <li>상대 없음({@code EMPTY}·{@code NUMBER_ONLY})·거절·탈퇴: 받을 사람이 없어 요청을 열지 않는다. 혼자 잠금은 잠금 작업에서
     *       {@code canGoSoloDirectly}로 판단한다
     * </ul>
     *
     * <p>상대 자리와 상관없이 작성 중에 붙인 사진의 {@code released_at}을 행동 시각으로 채운다(#295). 사진이 보이는지는 그 시각과 공유 중단
     * 시각으로 photo가 판단해서 항목({@code InspectionSight})과 같은 때 보이고 같은 때 가려진다.
     *
     * <p><b>현재 구현 범위의 한계</b>: 상대 측이 "지금까지 쓴 것으로" 보냈을 때 기준 측에 가는 서비스 안 알림(06 4-7)은 아직 보내지 않는다. 지금
     * {@code InAppNotifier}는 메모리에만 쌓는 가짜라 사용자가 볼 수 없어서, 실제 저장이 들어오는 U3-2 뒤에 주제를 정해 연결한다(#116 D 제안
     * (가), #234 리뷰). 그때도 공유 중단 제한이 살아 있으면 보내지 않는다(불변식 18).
     *
     * <p>이 행동은 판을 보지 않아 내용 지문이 없다(안내서 3-3).
     */
    @Override
    public SavedState save(ActionCommand command, InspectionState next) {
      Inspection row = row(command);
      row.changeStatus(next.status());
      PartySide author = row.getAuthorSide();
      RoomParties room = parties.read(command.roomRecordId(), author);
      Instant at = requestContext.at();
      RequestTarget target = RequestTarget.of(TargetKind.INSPECTION, row.getId());
      boolean stopped = InspectionSight.sharingStopped(room);
      if (!stopped && room.counterpart().presence() == PartyPresence.CONNECTED) {
        row.markShown(at);
      }
      // 작성 중에 붙인 사진도 항목과 같은 때부터 보인다. 공유를 그만둔 뒤면 그 시각보다 뒤라 계속 가려진다(#295, 불변식 18)
      photos.release(command.roomRecordId(), row.getId(), at);
      if (InspectionSight.reachable(room)) {
        waits.open(
            new OpenRequestCommand(
                command.roomRecordId(), RequestKind.INSPECTION_CONFIRM, target, author));
      }
      return SavedState.unchanged();
    }
  }
}
