package com.chilsuitjo.back.inspection.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * <p>지금은 입주·퇴거 점검 시작(INSP-1a·2·3), 확인 요청(INSP-1f), 서버 잠금({@code LOCK}, INSP-1b·1c·1d)만 있다. 퇴거 점검
 * 마침({@code FINISH_INSPECTION})은 그 기능을 만들 때 더하고, 그 전까지 다른 행동은 {@code INVALID_STATE}다.
 */
@Component
public class InspectionTransitions implements Transitions<InspectionState> {

  /** 점검 상태를 바꾸는 행동. 보이는 범위를 다시 계산할 때({@code Replay.fold}) 이 행동만 넣는다. */
  public static final Set<ActionType> CHANGES =
      Set.of(ActionType.START_INSPECTION, ActionType.REQUEST_INSPECTION_CONFIRM, ActionType.LOCK);

  /** 작성 중이 이만큼 지나면 상대도 확인 요청을 보낼 수 있다({@code inspection.draft.stale-after}, 06 0-1). */
  private final Duration draftStaleAfter;

  /** 시작 시각을 날짜로 바꿀 때 쓰는 시간대({@code app.zone}). 입주일과 같은 달력으로 늦었는지 본다(INSP-2a). */
  private final ZoneId zone;

  public InspectionTransitions(
      @Value("${app.inspection.draft.stale-after:7d}") Duration draftStaleAfter,
      @Value("${app.zone}") ZoneId zone) {
    this.draftStaleAfter = Objects.requireNonNull(draftStaleAfter, "draftStaleAfter");
    this.zone = Objects.requireNonNull(zone, "zone");
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
      case LOCK -> lock(now);
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
    return requestAllowed(state, side, now);
  }

  /**
   * 확인 요청을 받을 수 있나. 버튼({@link #canRequestConfirm})과 실제 요청({@link #apply})이 이 함수 하나를 쓴다(06 4-0 "버튼과
   * 실제 요청은 서버의 같은 함수"). 거절은 모두 {@code INVALID_STATE}라 참·거짓으로 충분하다.
   *
   * <ul>
   *   <li>있는 점검이고 작성 중이며 행동하는 측을 안다
   *   <li>기준 측이거나, 시작 뒤 {@code inspection.draft.stale-after}가 지났다(이상). 이 기간은 요청할 때만 지금 설정으로 보고,
   *       재계산이면 기록된 요청을 그때 유효했던 것으로 다룬다({@link InspectionState#atRequest}, #234 리뷰)
   *   <li>지금 호실 단계에서 점검 확인 요청을 열 수 없다고 확인되지 않았다
   * </ul>
   */
  private boolean requestAllowed(InspectionState state, PartySide side, Instant at) {
    return state.exists()
        && state.status() == InspectionStatus.DRAFT
        && side != null
        && (side == state.authorSide() || !state.atRequest() || draftIsStale(state, at))
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
   * <p>늦은 입주 점검(INSP-2a): 시작한 날({@code app.zone} 달력)이 입주일보다 뒤면 누가 쓰든 {@code late}다. 입주일 당일은 늦지 않다.
   * 입주일은 이 행동이 채운 값, 없으면 호실에 이미 있던 값이다.
   *
   * <p>퇴거 점검(INSP-3, 03 H "퇴거 점검"): 입주 점검과 같은 규칙으로 하나만, 먼저 시작한 측이 기준이다. 입주 점검이 없어도 시작한다(살다가 가입한
   * 경우). 거주 종료 뒤에도 정산 완료 전이면 시작한다(03 A "사진 올리기"에 퇴거 점검, 정산 완료는 pipeline이 막음). 입주일은 입주 점검만 채우므로 보내면
   * {@code VALIDATION_FAILED}이고 늦은 표시는 붙지 않는다.
   */
  private InspectionState start(InspectionState now, ActionEntry action) {
    if (now.exists()) {
      throw new BusinessException(ErrorCode.INSPECTION_EXISTS);
    }
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    StartInspectionPayload in = action.payloadAs(StartInspectionPayload.class);
    boolean dateGiven = in.moveInDate() != null;
    if (in.kind() == InspectionKind.MOVE_OUT) {
      if (dateGiven) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
      }
      return started(now, action, in.kind(), now.moveInDateMissing(), false);
    }
    if (Boolean.TRUE.equals(now.roomEnded())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (Boolean.TRUE.equals(now.moveInDateMissing()) && !dateGiven) {
      throw new BusinessException(ErrorCode.MOVE_IN_DATE_REQUIRED);
    }
    if (Boolean.FALSE.equals(now.moveInDateMissing()) && dateGiven) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    LocalDate moveInDate = dateGiven ? LocalDate.parse(in.moveInDate()) : now.moveInDate();
    Boolean late =
        moveInDate == null
            ? null
            : action.createdAt().atZone(zone).toLocalDate().isAfter(moveInDate);
    return started(now, action, in.kind(), now.moveInDateMissing() == null ? null : false, late);
  }

  private static InspectionState started(
      InspectionState now,
      ActionEntry action,
      InspectionKind kind,
      Boolean moveInDateMissing,
      Boolean late) {
    return new InspectionState(
        kind,
        InspectionStatus.DRAFT,
        action.actorSide(),
        action.createdAt(),
        moveInDateMissing,
        now.roomEnded(),
        now.canOpenConfirm(),
        null,
        late);
  }

  /**
   * 확인 요청(06 4-7, INSP-1f): 작성 중 → 확인 요청. 기준 측은 작성 중이면 언제든, 상대 측은 시작 뒤 {@code
   * inspection.draft.stale-after}가 지나야 "지금까지 쓴 것으로" 보낼 수 있다(03 6-6 2번, 그 전이면 {@code
   * INVALID_STATE}). 항목이 없어도 보낸다("하자 기록 없음", 03 H "잠기는 조건"). 지금 호실 단계에서 점검 확인 요청을 열 수 없으면 {@code
   * INVALID_STATE}다.
   *
   * <p>누가 눌렀든 요청한 측은 기준 측이다(답하는 측의 반대가 요청한 측, {@code WaitService.answered}, #218에서 정함). 그 요청을 실제로
   * 열지는 상대가 볼 수 있는지에 따라 {@code save}가 정한다.
   *
   * <p>판단은 버튼과 같은 {@link #requestAllowed}다. 작성 중 기간은 요청할 때만 지금 설정으로 보고, 재계산이면 기록된 요청을 그때 유효했던 것으로
   * 다룬다: 설정을 바꾼 뒤에도 과거 점검이 같은 상태로 다시 계산돼야 해서다(#234 리뷰).
   */
  private InspectionState requestConfirm(InspectionState now, ActionEntry action) {
    if (!requestAllowed(now, action.actorSide(), action.createdAt())) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now.withStatus(InspectionStatus.REQUESTED);
  }

  /**
   * 서버 잠금({@code LOCK}, 03 H "잠기는 조건"): 확인 요청 → 잠김. 잠글지는 항목 응답을 남기는 {@code save}가 {@link
   * InspectionLock}으로 정하고 딸린 서버 행동으로 남긴다. 여기서는 그 기록을 다시 계산할 때({@code Replay.fold}) 상태만 옮긴다. 확인 요청이
   * 아니면 {@code INVALID_STATE}다(잠금은 한 번만, 작성 중에는 잠기지 않음).
   */
  private static InspectionState lock(InspectionState now) {
    if (now.status() != InspectionStatus.REQUESTED) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now.withStatus(InspectionStatus.LOCKED);
  }

  /**
   * 점검 시작 뒤 {@code inspection.draft.stale-after}가 지났나(시작 + 기간이 된 때부터, 다른 7일 간격과 같은 이상 경계). 시작 시각을
   * 모르면 아니라고 본다: 시작·재계산 모두 {@code START_INSPECTION}이 채워 늘 있고, 모르는 채로 상대에게 열어 주는 것보다 막는 쪽이 안전하다.
   */
  private boolean draftIsStale(InspectionState state, Instant at) {
    Instant from = counterpartCanRequestAt(state);
    return from != null && !at.isBefore(from);
  }
}
