package com.chilsuitjo.back.settlement.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 정산의 규칙(03 I·1-4 "정산 끝내기"·A, 06 SET-5, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다.
 * Repository·{@code Clock}·다른 서비스를 부르지 않는다. 시각은 {@code action.createdAt()}이다.
 *
 * <p>정산 완료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3). 정산 열기는 거주 중·거주 종료 모두, 끝내기는 거주 종료 뒤에만
 * 된다(03 A).
 *
 * <p>버튼({@code availableActions})과 실제 요청은 아래 {@code can*} 함수를 함께 쓴다(06 4-0 "서버의 같은 함수").
 */
@Component
public class SettlementTransitions implements Transitions<SettlementState> {

  /** 정산 상태를 바꾸는 행동. */
  public static final Set<ActionType> CHANGES =
      Set.of(
          ActionType.OPEN_SETTLEMENT,
          ActionType.PROPOSE_FINISH,
          ActionType.CONFIRM_FINISH,
          ActionType.NOT_YET_FINISH,
          ActionType.FINISH_ALONE,
          ActionType.CLOSE_FINISH_REQUEST);

  /** [아직 안 끝났어요] 뒤 다시 요청 간격({@code settle.finish.reask.interval}, 06 0-1). */
  private final Duration reaskInterval;

  public SettlementTransitions(
      @Value("${app.settle.finish.reask.interval:7d}") Duration reaskInterval) {
    this.reaskInterval = Objects.requireNonNull(reaskInterval, "reaskInterval");
  }

  @Override
  public SettlementState initial() {
    return SettlementState.NONE;
  }

  @Override
  public SettlementState apply(SettlementState now, ActionEntry action) {
    return switch (action.type()) {
      case OPEN_SETTLEMENT -> open(now);
      case PROPOSE_FINISH -> propose(now, action);
      case CONFIRM_FINISH -> confirm(now, action);
      case NOT_YET_FINISH -> notYet(now, action);
      case FINISH_ALONE -> alone(now, action);
      case CLOSE_FINISH_REQUEST -> now.withFinishRequestedBy(null);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /** 정산 열기(03 I). 호실마다 하나라 이미 열렸으면 {@code SETTLEMENT_EXISTS}다. */
  private static SettlementState open(SettlementState now) {
    if (now.opened()) {
      throw new BusinessException(ErrorCode.SETTLEMENT_EXISTS);
    }
    return SettlementState.OPENED;
  }

  // ---- 끝내기 제안(06 4-8 finish) ----

  /** 이 측이 지금 끝내기를 제안할 수 있나. 막히면 그 오류, 되면 null. */
  public ErrorCode proposeBlocked(SettlementState now, PartySide side, Instant at) {
    SettlementState.Facts facts = now.facts();
    if (!now.opened() || now.settled()) {
      return ErrorCode.INVALID_STATE;
    }
    if (facts == null) {
      return null;
    }
    if (!facts.ended()) {
      return ErrorCode.INVALID_STATE;
    }
    if (facts.sharingStopped()) {
      return ErrorCode.SHARING_STOPPED;
    }
    // 끝내기 요청은 연결한 상대에게만 간다. 상대가 없거나 연결 전이면 혼자 끝내기로 한다(03 1-4 "연결 전 상대" ③)
    if (!facts.counterpartConnected() || now.finishRequestedBy() != null) {
      return ErrorCode.INVALID_STATE;
    }
    if (facts.lastNotYetAt() != null && at.isBefore(facts.lastNotYetAt().plus(reaskInterval))) {
      return ErrorCode.ASKED_RECENTLY;
    }
    return null;
  }

  private SettlementState propose(SettlementState now, ActionEntry action) {
    throwIf(proposeBlocked(now, action.actorSide(), action.createdAt()));
    return now.withFinishRequestedBy(action.actorSide());
  }

  // ---- 끝내기 확인(06 4-8 finish/confirm) ----

  /** 이 측이 지금 끝내기 요청에 답할 수 있나(확인·[아직 안 끝났어요] 공통). */
  public static boolean canAnswer(SettlementState now, PartySide side) {
    return now.opened()
        && !now.settled()
        && now.finishRequestedBy() != null
        && now.finishRequestedBy() != side;
  }

  /**
   * 끝내기 확인 → 정산 완료(SET-5a). 본 정산 모습 번호가 지금과 다르면 {@code VERSION_CONFLICT}(SET-5e, 불변식 19. 그사이 정산이
   * 바뀌어 요청은 이미 닫혔음), 열린 요청이 없으면 {@code INVALID_STATE}, 요청한 측이면 {@code NOT_COUNTERPART_SIDE}다.
   */
  private static SettlementState confirm(SettlementState now, ActionEntry action) {
    SettlementState.Facts facts = now.facts();
    if (facts != null
        && (action.seenVersionNo() == null || action.seenVersionNo() != facts.snapshotNo())) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    requireAnswerable(now, action.actorSide());
    return now.asSettled();
  }

  /** [아직 안 끝났어요](사유, SET-5c): 끝내기 요청이 닫히고 혼자 끝내기는 열리지 않는다. */
  private static SettlementState notYet(SettlementState now, ActionEntry action) {
    requireAnswerable(now, action.actorSide());
    if (action.reason() == null || action.reason().isBlank()) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    return now.withFinishRequestedBy(null);
  }

  private static void requireAnswerable(SettlementState now, PartySide side) {
    if (!now.opened() || now.settled() || now.finishRequestedBy() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (now.finishRequestedBy() == side) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
  }

  // ---- 혼자 끝내기(06 4-8 finish/alone) ----

  /**
   * 이 측이 지금 혼자 끝낼 수 있나. 막히면 그 오류, 되면 null. 거주 종료 뒤에만, 공유 중단 동안은 안 되고({@code SHARING_STOPPED}), 혼자
   * 진행 이유가 없으면({@code SOLO_NOT_ALLOWED}: 72시간 전, 상대가 [아직 안 끝났어요]로 답함, 정산이 바뀌어 닫힘, 상대가 보낸 요청이 기다리는
   * 중) 안 된다.
   */
  public static ErrorCode aloneBlocked(SettlementState now, PartySide side) {
    SettlementState.Facts facts = now.facts();
    if (!now.opened() || now.settled()) {
      return ErrorCode.INVALID_STATE;
    }
    if (facts == null) {
      return null;
    }
    if (!facts.ended()) {
      return ErrorCode.INVALID_STATE;
    }
    if (facts.sharingStopped()) {
      return ErrorCode.SHARING_STOPPED;
    }
    if (facts.soloReason() == null
        || (now.finishRequestedBy() != null && now.finishRequestedBy() != side)) {
      return ErrorCode.SOLO_NOT_ALLOWED;
    }
    return null;
  }

  private static SettlementState alone(SettlementState now, ActionEntry action) {
    throwIf(aloneBlocked(now, action.actorSide()));
    return now.asSettled();
  }

  private static void throwIf(ErrorCode code) {
    if (code != null) {
      throw new BusinessException(code);
    }
  }
}
