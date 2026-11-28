package com.chilsuitjo.back.room.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 호실 기록 상태 전이(03 A). 호실 기록 만들기({@code CREATE_ROOM})와 거주 종료(06 ROOM-9)를 받는다. 정산 완료 같은 다른 호실 행동은 그
 * 기능을 만들 때 더한다.
 *
 * <p>거주 종료(03 A "이사함 기록", 3장 권한 표):
 *
 * <ul>
 *   <li>{@code MOVE_OUT}: 세입자 측이면 바로 거주 종료. 집주인 측은 물을 세입자가 없을 때만({@link
 *       RoomState#landlordEndsAlone})
 *   <li>{@code REQUEST_END}: 집주인 측. 세입자가 답할 수 있어야 하고(연결됨·공유 중단) 이미 보낸 요청이 없어야 한다. 거주 중 그대로
 *   <li>{@code CONFIRM_END}: 세입자 측이 열린 거주 종료 요청에 "맞아요" → 거주 종료
 *   <li>{@code REJECT_END}: 세입자 측이 "아직 살고 있어요"(사유 필수) → 거주 중 그대로
 * </ul>
 *
 * <p>모두 거주 중일 때만 된다. 거주 종료를 되돌리는 전이는 없다(03 6-3 "남는 한계"). 맞지 않으면 {@code INVALID_STATE}다.
 */
@Component
public class RoomTransitions implements Transitions<RoomState> {

  @Override
  public RoomState initial() {
    return new RoomState(null);
  }

  @Override
  public RoomState apply(RoomState now, ActionEntry action) {
    return switch (action.type()) {
      case CREATE_ROOM -> {
        if (now.status() != null) {
          throw invalidState();
        }
        yield new RoomState(RoomStatus.LIVING); // 03 A: [*] → 거주 중
      }
      case MOVE_OUT -> {
        if (!canMoveOut(now, action.actorSide())) {
          throw invalidState(); // 물을 세입자가 있으면 거주 종료 요청이다
        }
        yield now.withStatus(RoomStatus.ENDED, false);
      }
      case REQUEST_END -> {
        if (!canRequestEnd(now, action.actorSide())) {
          throw invalidState();
        }
        yield now.withStatus(RoomStatus.LIVING, true);
      }
      case CONFIRM_END -> {
        if (!canAnswerEnd(now, action.actorSide())) {
          throw invalidState();
        }
        yield now.withStatus(RoomStatus.ENDED, false);
      }
      case REJECT_END -> {
        if (!canAnswerEnd(now, action.actorSide())) {
          throw invalidState();
        }
        if (action.reason() == null || action.reason().isBlank()) {
          throw new BusinessException(ErrorCode.REASON_REQUIRED);
        }
        yield now.withStatus(RoomStatus.LIVING, false);
      }
      default -> throw invalidState();
    };
  }

  /**
   * 이 측이 지금 {@code MOVE_OUT}(바로 거주 종료)을 남길 수 있나. {@link #apply}와 호실 상세 {@code availableActions}(06
   * 4-0)가 같이 쓴다. 집주인 측은 물을 세입자가 없을 때만이다({@link RoomState#landlordEndsAlone}).
   */
  public static boolean canMoveOut(RoomState now, PartySide side) {
    return now.status() == RoomStatus.LIVING
        && !(side == PartySide.LANDLORD && Boolean.FALSE.equals(now.landlordEndsAlone()));
  }

  /** 이 측이 지금 거주 종료 요청({@code REQUEST_END})을 보낼 수 있나. 집주인 측, 답할 수 있는 세입자, 아직 열린 요청 없음. */
  public static boolean canRequestEnd(RoomState now, PartySide side) {
    return now.status() == RoomStatus.LIVING
        && side == PartySide.LANDLORD
        && !Boolean.FALSE.equals(now.tenantAnswers())
        && !Boolean.TRUE.equals(now.endRequestOpen());
  }

  /** 이 측이 지금 거주 종료 요청에 답할 수 있나({@code CONFIRM_END}·{@code REJECT_END}). 세입자 측, 열린 요청 있음. */
  public static boolean canAnswerEnd(RoomState now, PartySide side) {
    return now.status() == RoomStatus.LIVING
        && side == PartySide.TENANT
        && !Boolean.FALSE.equals(now.endRequestOpen());
  }

  private static BusinessException invalidState() {
    return new BusinessException(ErrorCode.INVALID_STATE);
  }
}
