package com.chilsuitjo.back.inspection.repository;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.inspection.entity.Inspection;
import com.chilsuitjo.back.inspection.rule.InspectionKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.notification.rule.RoomPhase;
import com.chilsuitjo.back.notification.service.CounterpartReader;
import com.chilsuitjo.back.room.service.RoomMoveInDates;
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

  /** 점검 시작(06 INSP-1a·2b)의 저장 방법. 종류와 채울 입주일은 payload에 있다. */
  public StateStore<InspectionState> starting() {
    return new Starting();
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
      return stateOf(
          inspections
              .findByIdAndRoomRecordId(target.id(), command.roomRecordId())
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }
    InspectionKind kind = kindOf(command);
    return inspections
        .findByRoomRecordIdAndKind(command.roomRecordId(), kind)
        .map(InspectionStateStore::stateOf)
        .orElseGet(() -> notStarted(command));
  }

  private InspectionState notStarted(ActionCommand command) {
    boolean dateMissing = moveInDates.find(command.roomRecordId()).isEmpty();
    RoomPhase phase =
        parties
            .read(command.roomRecordId(), Objects.requireNonNull(command.actor().side(), "side"))
            .phase();
    return InspectionState.notStarted(dateMissing, phase != RoomPhase.LIVING);
  }

  private static InspectionState stateOf(Inspection row) {
    return new InspectionState(row.getKind(), row.getStatus(), row.getAuthorSide(), null, null);
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
}
