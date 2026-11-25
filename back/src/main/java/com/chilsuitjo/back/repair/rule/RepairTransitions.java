package com.chilsuitjo.back.repair.rule;

import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.AnswerPayload;
import com.chilsuitjo.back.action.rule.AnswerSupport;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 수리 건의 규칙(03 D, 09 2장 {@code rule/}). 지금 상태와 행동으로 다음 상태를 정하는 순수 함수다. Repository·{@code Clock}·다른
 * 서비스를 부르지 않는다. 시각이 필요하면 {@code action.createdAt()}을 쓴다.
 *
 * <p>정산 완료·거주 종료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3). 거주 종료 뒤 새 수리 건은 {@code REGISTER}의
 * {@code blockedAfterEnded}로 막힌다(06 REP-1c).
 *
 * <p>지금은 하자 등록(REP-1), 하자 응답·보충(REP-2), 수리 시작(REP-4)·종료 요청·종료 확인·아직 안 됐어요(REP-5a·5b)가 있다. 혼자 종료·다시
 * 열기(REP-5c~e) 등은 그 기능을 만들 때 더한다(#174). 그 전까지 다른 행동은 {@code INVALID_STATE}다.
 */
@Component
public class RepairTransitions implements Transitions<RepairState> {

  /** 강행 표시의 행동 payload 키(05 2-3). */
  public static final String WHILE_OPEN_OBJECTION = "while_open_objection";

  /** 한 기록에 보충하고 다시 확인 요청하는 간격({@code rerequest.interval}, 06 0-1). */
  private final Duration rerequestInterval;

  public RepairTransitions(@Value("${app.rerequest.interval:7d}") Duration rerequestInterval) {
    this.rerequestInterval = Objects.requireNonNull(rerequestInterval, "rerequestInterval");
  }

  @Override
  public RepairState initial() {
    return RepairState.NONE;
  }

  /**
   * 이 측이 지금 이 수리 건에 할 수 있는 행동(상세 조회의 {@code availableActions}, 06 4-0). {@link #apply}가 받아 주는지와 같은
   * 판단이다: 하자 응답은 {@code AnswerSupport.canAnswer}와 상대가 볼 수 있는 기록인지, 보충은 {@code
   * AnswerSupport.canRerequest}(근거는 더한다고 봄). 호실 단위 거절(정산 완료·공유 중단 등)은 여기서 보지 않는다({@code
   * RoomGate.rejection}).
   *
   * <p>수리 진행은 시작 전이면 시작, 처리 중이면 종료 요청(종료 확인 요청을 열 수 있을 때), 종료 확인 대기면 요청의 상대 측에 종료 확인·아직 안 됐어요다. 혼자
   * 종료·다시 열기 등은 그 기능을 만들 때 {@link #apply}와 함께 더한다.
   *
   * @param now 지금 시각(7일 간격 판단). 행동이 아니라 조회라서 부르는 쪽이 {@code Clock}으로 넘긴다
   */
  public List<ActionType> available(RepairState state, PartySide side, Instant now) {
    if (!state.exists() || side == null) {
      return List.of();
    }
    List<ActionType> result = new ArrayList<>();
    if (AnswerSupport.canAnswer(state.defect(), side) && !state.hiddenFromCounterpartKnown()) {
      result.add(ActionType.RESPOND_DEFECT);
    }
    if (AnswerSupport.canRerequest(
        state.defect(), side, !state.defectConfirmBlocked(), now, rerequestInterval)) {
      result.add(ActionType.SUPPLEMENT);
    }
    if (state.hiddenFromCounterpartKnown() && side != state.registeredBy()) {
      return List.copyOf(result);
    }
    switch (state.status()) {
      case NOT_STARTED -> result.add(ActionType.START_REPAIR);
      case IN_PROGRESS -> {
        if (!state.closeConfirmBlocked()) {
          result.add(ActionType.REQUEST_CLOSE);
        }
      }
      case CLOSE_WAIT -> {
        if (side != state.closeRequestedBy()) {
          result.add(ActionType.CONFIRM_CLOSE);
          result.add(ActionType.NOT_YET);
        }
      }
      case DONE -> {}
    }
    return List.copyOf(result);
  }

  /**
   * 강행 표시(03 G "강행 표시", 05 2-3 payload {@code while_open_objection}): 보류·이견이 열린 채 수리를 시작하거나
   * 종료(요청·확인)하면 막지 않고 그때의 하자 인정({@code NEED_MORE}/{@code DISPUTE})을 행동에 남긴다. 확인 전("○○ 님 확인 전에
   * 시작했어요")은 하자 인정 {@code PENDING}이라 화면이 그 시각의 하자 인정으로 보이고 payload에는 넣지 않는다(05 2-3은
   * NEED_MORE/DISPUTE만). 이 행동들은 하자 인정을 바꾸지 않으므로 {@code state}는 행동 전후 어느 쪽이어도 같다.
   *
   * @return 남길 값. 강행이 아니거나 강행 표시를 남기는 행동이 아니면 빈 값
   */
  public static Optional<AckState> openObjection(ActionType type, RepairState state) {
    boolean marked =
        type == ActionType.START_REPAIR
            || type == ActionType.REQUEST_CLOSE
            || type == ActionType.CONFIRM_CLOSE;
    AckState ack = state.defectAck();
    if (marked && (ack == AckState.NEED_MORE || ack == AckState.DISPUTE)) {
      return Optional.of(ack);
    }
    return Optional.empty();
  }

  /** 다음에 보충할 수 있는 시각("○월 ○일부터 다시 요청할 수 있어요"). 보충한 적이 없으면 null. */
  public Instant nextRerequestAt(RepairState state) {
    return AnswerSupport.nextRerequestAt(state.defect(), rerequestInterval);
  }

  @Override
  public RepairState apply(RepairState now, ActionEntry action) {
    return switch (action.type()) {
      case REGISTER -> register(now, action);
      case RESPOND_DEFECT -> respond(now, action);
      case SUPPLEMENT -> supplement(now, action);
      case START_REPAIR -> start(now, action);
      case REQUEST_CLOSE -> requestClose(now, action);
      case CONFIRM_CLOSE -> confirmClose(now, action);
      case NOT_YET -> notYet(now, action);
      default -> throw new BusinessException(ErrorCode.INVALID_STATE);
    };
  }

  /**
   * 하자 등록: 시작 전 · 확인 기다림 · 부담 미정(06 REP-1a, 05 2-5). 등록한 측이 기록한 측이다. 측이 없는 주체(서버·운영)는 수리 건을 만들 수
   * 없다({@code repair_case.created_by_side} NOT NULL). 판 #1이 지금 판이다.
   */
  private static RepairState register(RepairState now, ActionEntry action) {
    if (now.exists() || action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return new RepairState(
        RepairStatus.NOT_STARTED,
        AckState.PENDING,
        MoneyStatus.UNDECIDED,
        action.actorSide(),
        1,
        null,
        null,
        null,
        now.canOpenDefectConfirm(),
        now.canOpenCloseConfirm(),
        now.hiddenFromCounterpart());
  }

  /**
   * 하자 응답(06 REP-2a~d·f, REP-9a): 상대 측만, 본 판 번호가 지금 판과 같아야 하고, 맞아요는 끝이며, 보류·이견에서는 맞아요로만, 보류·이견은 사유
   * 필수({@code AnswerSupport.answer}). 수리 진행은 그대로다(REP-2a). 사유는 행동 기록의 {@code reason}이다(#155).
   *
   * <p>상대가 볼 수 없는 기록(어느 쪽이든 공유를 그만둔 뒤 등록한 하자, 03 불변식 18)에는 상대가 답할 수 없다: {@code INVALID_STATE}(안내서
   * 8-3). 그만둔 사람 자신은 pipeline이 {@code SHARING_STOPPED}로 먼저 막지만, 남은 사람이 상대의 "나만 보는 기록"에 답하는 것은 여기서
   * 막는다. 등록한 측이 부르면 그대로 {@code NOT_COUNTERPART_SIDE}다.
   *
   * <p>거주 종료 뒤에도 정산 완료 전까지 답할 수 있다(늦은 답, decisions 2026-11-19). 정산 완료는 pipeline이 막는다.
   */
  private static RepairState respond(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    AnswerPayload in = action.payloadAs(AnswerPayload.class);
    AckState next =
        AnswerSupport.answer(
            now.defect(), action.actorSide(), in.answer(), action.reason(), action.seenVersionNo());
    return now.withDefectAck(next);
  }

  /**
   * 보충하고 다시 확인 요청(06 REP-2e): 등록한 측만, 보류·이견일 때만, 지금 하자 확인 요청을 열 수 있을 때만(거주 종료 뒤 막힘, 03 K), 7일에 한
   * 번({@code AnswerSupport.rerequest}). 근거(사진이나 설명)는 서비스가 pipeline 전에 본다. 하자 인정은 확인 기다림으로 돌아간다.
   * 재계산이라 요청을 열 수 있는지 모르면 그때 서버가 확인했다고 본다({@link RepairState} "요청할 때만 아는 값").
   */
  private RepairState supplement(RepairState now, ActionEntry action) {
    requireExists(now);
    AnswerSupport.Rerequested result =
        AnswerSupport.rerequest(
            now.defect(),
            action.actorSide(),
            true,
            !now.defectConfirmBlocked(),
            action.createdAt(),
            rerequestInterval);
    return now.withDefectAck(result.state()).withLastRerequestAt(result.rerequestedAt());
  }

  /**
   * 수리 시작(06 REP-4a): 당사자 누구든, 시작 전에서만 처리 중으로. 하자 인정이 맞아요가 아니어도(확인 전·보류·이견) 시작한다. 강행 표시("확인 전에
   * 시작했어요" 등)는 시작한 때의 하자 인정으로 화면이 보이므로 하자 인정은 바꾸지 않는다. 거주 종료 뒤에도 정산 완료 전까지는 사실을 남길 수 있다.
   */
  private static RepairState start(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (now.status() != RepairStatus.NOT_STARTED) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now.withProgress(RepairStatus.IN_PROGRESS, null, null);
  }

  /**
   * 종료 요청(06 REP-5): 당사자 누구든, 처리 중에서만, 지금 종료 확인 요청을 열 수 있을 때만(거주 종료 뒤 막힘, decisions 2026-11-19).
   * 요청한 측을 남기고 종료 확인 대기가 된다. 재계산이라 열 수 있는지 모르면 그때 서버가 확인했다고 본다.
   */
  private static RepairState requestClose(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (now.status() != RepairStatus.IN_PROGRESS || now.closeConfirmBlocked()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    return now.withProgress(RepairStatus.CLOSE_WAIT, action.actorSide(), null);
  }

  /**
   * 종료 확인(06 REP-5a): 요청의 상대 측만, 종료 확인 대기에서만 → 처리 완료(양측 확인). 하자 인정이 확인 기다림이면 같은 요청의 다음 단계 {@code
   * RESPOND_DEFECT}가 맞아요를 남긴다(REP-3a, decisions 2026-11-17). 거주 종료 뒤에도 정산 완료 전까지 늦게 확인할 수 있다(03 6-6
   * 1번).
   */
  private static RepairState confirmClose(RepairState now, ActionEntry action) {
    requireCloseAnswer(now, action);
    return now.withProgress(RepairStatus.DONE, null, ClosedBy.BOTH);
  }

  /** 아직 안 됐어요(06 REP-5b): 요청의 상대 측만, 사유 필수 → 처리 중으로 돌아가고 종료 요청은 닫힌다. */
  private static RepairState notYet(RepairState now, ActionEntry action) {
    requireCloseAnswer(now, action);
    if (action.reason() == null || action.reason().isBlank()) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    return now.withProgress(RepairStatus.IN_PROGRESS, null, null);
  }

  /** 종료 요청에 답하기: 종료 확인 대기 → 요청한 측이 아님(같은 측이면 {@code NOT_COUNTERPART_SIDE}). */
  private static void requireCloseAnswer(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (now.status() != RepairStatus.CLOSE_WAIT) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (action.actorSide() == now.closeRequestedBy()) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
  }

  private static void requireExists(RepairState now) {
    if (!now.exists()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
  }

  /**
   * 있는 기록이고, 상대가 볼 수 없는 기록(어느 쪽이든 공유를 그만둔 뒤 등록, 03 불변식 18)이면 등록한 측만 행동할 수 있다. 상대가 부르면 {@code
   * INVALID_STATE}(안내서 8-3).
   */
  private static void requireVisible(RepairState now, ActionEntry action) {
    requireExists(now);
    if (now.hiddenFromCounterpartKnown() && action.actorSide() != now.registeredBy()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
  }

  /** 측이 있는 주체(회원·링크)만. 서버·운영 주체는 수리 진행을 남기지 않는다. */
  private static void requireSide(ActionEntry action) {
    if (action.actorSide() == null) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
  }
}
