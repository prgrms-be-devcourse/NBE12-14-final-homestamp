package com.chilsuitjo.back.inspection.rule;

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
 * 점검의 규칙(03 H, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다. Repository·{@code Clock}·다른
 * 서비스를 부르지 않는다. 시각은 {@code action.createdAt()}이다.
 *
 * <p>정산 완료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3).
 *
 * <p>지금은 입주 점검 시작(INSP-1a·2b)과 확인 요청(INSP-1f)만 있다. 잠금(INSP-1b~e)은 그 기능을 만들 때 더하고, 그 전까지 다른 행동은
 * {@code INVALID_STATE}다. 퇴거 점검(INSP-3)·늦은 입주 표시({@code late}, INSP-2a)는 B가 만든다(07 WBS 3주차).
 */
@Component
public class InspectionTransitions implements Transitions<InspectionState> {

  /** 점검 상태를 바꾸는 행동. 보이는 범위를 다시 계산할 때({@code Replay.fold}) 이 행동만 넣는다. */
  public static final Set<ActionType> CHANGES =
      Set.of(ActionType.START_INSPECTION, ActionType.REQUEST_INSPECTION_CONFIRM);

  /** 작성 중이 이만큼 지나면 상대도 확인 요청을 보낼 수 있다({@code inspection.draft.stale-after}, 06 0-1). */
  private final Duration draftStaleAfter;

  public InspectionTransitions(
      @Value("${app.inspection.draft.stale-after:7d}") Duration draftStaleAfter) {
    this.draftStaleAfter = Objects.requireNonNull(draftStaleAfter, "draftStaleAfter");
  }

  @Override
  public InspectionState initial() {
    return InspectionState.NONE;
  }

  @Override
  public InspectionState apply(InspectionState now, ActionEntry action) {
    return switch (action.type()) {
      case START_INSPECTION -> start(now, action);
      case REQUEST_INSPECTION_CONFIRM -> requestConfirm(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 이 측이 지금 확인 요청을 보낼 수 있나(상세 조회의 {@code availableActions}, 06 4-0). {@link #apply}의 확인 요청과 같은
   * 판단이다. 호실 단위 거절(정산 완료·공유 중단 등)은 여기서 보지 않는다({@code RoomGate.rejection}).
   *
   * @param now 지금 시각(작성 중 기간 판단). 행동이 아니라 조회라서 부르는 쪽이 {@code Clock}으로 넘긴다
   */
  public boolean canRequestConfirm(InspectionState state, PartySide side, Instant now) {
    return state.exists()
        && state.status() == InspectionStatus.DRAFT
        && side != null
        && (side == state.authorSide() || draftIsStale(state, now))
        && !state.confirmBlocked();
  }

  /** 상대도 확인 요청을 보낼 수 있게 되는 시각(화면의 "작성 중이에요 · 8일째"). 시작 시각을 모르면 null. */
  public Instant counterpartCanRequestAt(InspectionState state) {
    return state.startedAt() == null ? null : state.startedAt().plus(draftStaleAfter);
  }

  /**
   * 점검 시작: 먼저 시작한 측이 기준 기록을 쓰고 작성 중에서 시작한다(03 H "입주 점검 기준 기록"). 호실을 잠근 뒤 읽으므로 동시에 시작해도 먼저 저장된 쪽만
   * 기준이 되고 나머지는 {@code INSPECTION_EXISTS}다(1-3).
   *
   * <p>입주일(06 4-7, INSP-2b): 호실 입주일이 비어 있으면 함께 보내야 하고({@code MOVE_IN_DATE_REQUIRED}), 이미 있으면 보내지
   * 않는다({@code VALIDATION_FAILED}: 입주일을 바꾸는 것은 고치기, 03 H "늦은 입주 점검"). 그래서 payload의 입주일은 늘 "이 행동이 채운
   * 값"이다.
   *
   * <p>거주 종료 뒤에는 입주 점검을 새로 시작하지 않는다({@code INVALID_STATE}, 03 A "상태별로 할 수 있는 일"에 없음).
   *
   * <p><b>현재 구현 범위의 한계</b>: 퇴거 점검({@code MOVE_OUT})은 아직 만들지 않아 {@code INVALID_STATE}로 받지 않는다. 규칙이
   * 아니라 구현 전이라서다. 퇴거 점검(06 INSP-3, B 담당)을 만들 때 이 거절을 지운다.
   */
  private static InspectionState start(InspectionState now, ActionEntry action) {
    if (now.exists()) {
      throw new BusinessException(ErrorCode.INSPECTION_EXISTS);
    }
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    StartInspectionPayload in = action.payloadAs(StartInspectionPayload.class);
    if (in.kind() != InspectionKind.MOVE_IN) {
      // 현재 구현 범위의 한계: 퇴거 점검은 아직 없다(INSP-3)
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (Boolean.TRUE.equals(now.roomEnded())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    boolean dateGiven = in.moveInDate() != null;
    if (Boolean.TRUE.equals(now.moveInDateMissing()) && !dateGiven) {
      throw new BusinessException(ErrorCode.MOVE_IN_DATE_REQUIRED);
    }
    if (Boolean.FALSE.equals(now.moveInDateMissing()) && dateGiven) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return new InspectionState(
        in.kind(),
        InspectionStatus.DRAFT,
        action.actorSide(),
        action.createdAt(),
        now.moveInDateMissing() == null ? null : false,
        now.roomEnded(),
        now.canOpenConfirm());
  }

  /**
   * 확인 요청(06 4-7, INSP-1f): 작성 중 → 확인 요청. 기준 측은 작성 중이면 언제든, 상대 측은 시작 뒤 {@code
   * inspection.draft.stale-after}가 지나야 "지금까지 쓴 것으로" 보낼 수 있다(03 6-6 2번, 그 전이면 {@code
   * INVALID_STATE}). 항목이 없어도 보낸다("하자 기록 없음", 03 H "잠기는 조건"). 지금 호실 단계에서 점검 확인 요청을 열 수 없으면 {@code
   * INVALID_STATE}다.
   *
   * <p>누가 눌렀든 요청한 측은 기준 측이다(답하는 측의 반대가 요청한 측, {@code WaitService.answered}, #218에서 정함). 그 요청을 실제로
   * 열지는 상대가 볼 수 있는지에 따라 {@code save}가 정한다.
   */
  private InspectionState requestConfirm(InspectionState now, ActionEntry action) {
    if (!now.exists() || now.status() != InspectionStatus.DRAFT || action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (action.actorSide() != now.authorSide() && !draftIsStale(now, action.createdAt())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (now.confirmBlocked()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now.withStatus(InspectionStatus.REQUESTED);
  }

  /** 작성 중이 {@code inspection.draft.stale-after}를 넘었나. 시작 시각을 모르면 아니라고 본다. */
  private boolean draftIsStale(InspectionState state, Instant at) {
    Instant from = counterpartCanRequestAt(state);
    return from != null && !at.isBefore(from);
  }
}
