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
 * <p>정산 완료·거주 종료·합류 전 기록·내 공유 중단은 pipeline이 먼저 막는다(09 3-1 순서 3). 거주 종료 뒤 새 수리 건과 수리 시작은 {@code
 * blockedAfterEnded}로 막힌다(06 REP-1c, 03 A "상태별로 할 수 있는 일").
 *
 * <p>지금은 하자 등록(REP-1), 하자 응답·보충(REP-2), 수리 시작(REP-4)·종료 요청·종료 확인·아직 안 됐어요(REP-5a·5b), 물을 상대가 없을 때의
 * 혼자 종료와 혼자 끝낸 건의 늦은 종료 확인·이견(REP-5e·5f), 진행 상황 묻기·남기기와 [상황이 바뀌었어요](REP-6b·6c·6d)가 있다. 다시 열기({@code
 * REOPEN})는 없앴다(#113 ③', decisions 2026-11-25). 상황 변화 응답({@code RESPOND_SITUATION})은 대상이 진행 상황이라
 * {@code SituationTransitions}가 맡는다. 그 밖의 행동은 {@code INVALID_STATE}다.
 */
@Component
public class RepairTransitions implements Transitions<RepairState> {

  /** 강행 표시의 행동 payload 키(05 2-3). */
  public static final String WHILE_OPEN_OBJECTION = "while_open_objection";

  /** 한 기록에 보충하고 다시 확인 요청하는 간격({@code rerequest.interval}, 06 0-1). */
  private final Duration rerequestInterval;

  /** 한 건에 진행 상황을 다시 묻는 간격(누가 물었든, {@code progress.ask.interval}, 06 0-1). */
  private final Duration progressAskInterval;

  public RepairTransitions(
      @Value("${app.rerequest.interval:7d}") Duration rerequestInterval,
      @Value("${app.progress.ask.interval:7d}") Duration progressAskInterval) {
    this.rerequestInterval = Objects.requireNonNull(rerequestInterval, "rerequestInterval");
    this.progressAskInterval = Objects.requireNonNull(progressAskInterval, "progressAskInterval");
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
   * <p>수리 진행은 시작 전이면 시작, 처리 중이면 종료 요청(종료 확인 요청을 열 수 있을 때)과 혼자 종료(혼자 종료할 수 있다고 확인됐을 때만, {@code
   * canCloseAlone}), 종료 확인 대기면 요청의 상대 측에, 혼자 끝낸 처리 완료면 혼자 끝낸 측의 상대에게 종료 확인·아직 안 됐어요다.
   *
   * <p>그 뒤에 진행 상황 묻기(시작 전·처리 중, 묻기 요청을 열 수 있고 간격이 지났을 때), 진행 상황 남기기(언제든)가 온다(03 D).
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
        if (Boolean.TRUE.equals(state.canCloseAlone())) {
          result.add(ActionType.CLOSE_ALONE);
        }
      }
      case CLOSE_WAIT -> {
        if (side != state.closeRequestedBy()) {
          result.add(ActionType.CONFIRM_CLOSE);
          result.add(ActionType.NOT_YET);
        }
      }
      case DONE -> {
        if (state.closedAlone() && !closedBySide(state, side)) {
          result.add(ActionType.CONFIRM_CLOSE);
          result.add(ActionType.NOT_YET);
        }
      }
    }
    if (canAskProgress(state, now)) {
      result.add(ActionType.ASK_PROGRESS);
    }
    result.add(ActionType.NOTE_PROGRESS);
    return List.copyOf(result);
  }

  /** 다음에 진행 상황을 물을 수 있는 시각("○월 ○일부터 다시 물을 수 있어요"). 물은 적이 없으면 null. */
  public Instant nextProgressAskAt(RepairState state) {
    return state.lastProgressAskAt() == null
        ? null
        : state.lastProgressAskAt().plus(progressAskInterval);
  }

  private boolean canAskProgress(RepairState state, Instant now) {
    Instant next = nextProgressAskAt(state);
    return askableStatus(state.status())
        && !state.progressCheckBlocked()
        && (next == null || !now.isBefore(next));
  }

  /** 진행 상황을 물을 수 있는 수리 진행: 시작 전·처리 중(03 D "진행 상황 묻기"). */
  private static boolean askableStatus(RepairStatus status) {
    return status == RepairStatus.NOT_STARTED || status == RepairStatus.IN_PROGRESS;
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
            || type == ActionType.CONFIRM_CLOSE
            || type == ActionType.CLOSE_ALONE;
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
      case CLOSE_ALONE -> closeAlone(now, action);
      case ASK_PROGRESS -> askProgress(now, action);
      case NOTE_PROGRESS -> noteProgress(now, action);
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
        null,
        now.canOpenDefectConfirm(),
        now.canOpenCloseConfirm(),
        now.canOpenProgressCheck(),
        now.hiddenFromCounterpart(),
        null);
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
   * 재계산이라 요청을 열 수 있는지 모르면 그때 서버가 확인했다고 본다({@link RepairState} "요청할 때만 아는 값"). 7일도 요청할 때만 지금 설정으로
   * 보고, 재계산이면 간격 0으로 넘겨 기록된 보충을 다시 거절하지 않는다({@link RepairState#atRequest}).
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
            now.atRequest() ? rerequestInterval : Duration.ZERO);
    return now.withDefectAck(result.state()).withLastRerequestAt(result.rerequestedAt());
  }

  /**
   * 수리 시작(06 REP-4a): 당사자 누구든, 시작 전에서만 처리 중으로. 하자 인정이 맞아요가 아니어도(확인 전·보류·이견) 시작한다. 강행 표시("확인 전에
   * 시작했어요" 등)는 시작한 때의 하자 인정으로 화면이 보이므로 하자 인정은 바꾸지 않는다. 거주 종료 뒤에는 pipeline이 {@code ROOM_ENDED}로
   * 막는다(03 A, {@code ActionType.blockedAfterEnded}).
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
   * 종료 확인(06 REP-5a·5f): 종료 확인 대기면 요청의 상대 측, 혼자 끝낸 처리 완료면 혼자 끝낸 측의 상대 → 처리 완료(양측 확인). 혼자 끝낸 건에 남긴
   * 이견 뒤에도 된다(이견은 이력). 하자 인정이 확인 기다림이면 같은 요청의 다음 단계 {@code RESPOND_DEFECT}가 맞아요를 남긴다(REP-3a,
   * decisions 2026-11-17). 거주 종료 뒤에도 정산 완료 전까지 늦게 확인할 수 있다(03 6-6 1번).
   */
  private static RepairState confirmClose(RepairState now, ActionEntry action) {
    requireCloseAnswer(now, action);
    return now.withProgress(RepairStatus.DONE, null, ClosedBy.BOTH);
  }

  /**
   * 아직 안 됐어요(06 REP-5b·5f): 답할 수 있는 측은 종료 확인과 같고 사유 필수. 종료 확인 대기면 처리 중으로 돌아가고 종료 요청은 닫힌다. 혼자 끝낸 처리
   * 완료면 상태는 그대로이고 이견으로 남는다(무엇이 이견인지는 행동 기록이 원본, 상세 조회가 계산).
   */
  private static RepairState notYet(RepairState now, ActionEntry action) {
    requireCloseAnswer(now, action);
    if (action.reason() == null || action.reason().isBlank()) {
      throw new BusinessException(ErrorCode.REASON_REQUIRED);
    }
    if (now.status() == RepairStatus.DONE) {
      return now;
    }
    return now.withProgress(RepairStatus.IN_PROGRESS, null, null);
  }

  /**
   * 혼자 종료(06 REP-5e, 03 1-4 "물을 상대가 없는 건의 종료"): 당사자 누구든, 처리 중에서만, 물을 상대가 처음부터 없을 때만 종료 요청 없이 처리
   * 완료(○○ 확인)로. 할 수 있는지는 {@code WaitService.canGoSoloDirectly(CLOSE_CONFIRM)}의 결과({@code
   * canCloseAlone})를 그대로 쓰고 여기서 이유를 다시 거르지 않는다. 종료 확인 대기면 상대의 답을 기다리는 중이라 {@code
   * SOLO_NOT_ALLOWED}(decisions 2026-11-25, #228 리뷰), 시작 전·처리 완료면 {@code INVALID_STATE}. 재계산이라 모르면
   * 그때 서버가 확인했다고 본다. 거주 종료 뒤에는 pipeline이 {@code ROOM_ENDED}로 막는다({@code
   * ActionType.blockedAfterEnded}).
   */
  private static RepairState closeAlone(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (now.status() == RepairStatus.CLOSE_WAIT) {
      throw new BusinessException(ErrorCode.SOLO_NOT_ALLOWED);
    }
    if (now.status() != RepairStatus.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (now.closeAloneBlocked()) {
      throw new BusinessException(ErrorCode.SOLO_NOT_ALLOWED);
    }
    return now.withProgress(RepairStatus.DONE, null, ClosedBy.valueOf(action.actorSide().name()));
  }

  /**
   * 진행 상황 묻기(06 REP-6b, 03 D): 당사자 누구든, 시작 전·처리 중에서만, 지금 진행 상황 묻기 요청을 열 수 있을 때만(거주 중, 03 K), 한 건에
   * {@code progress.ask.interval}에 한 번(누가 물었든, decisions 2026-11-13). 그 안이면 {@code ASKED_RECENTLY}.
   * 수리 진행·하자 인정은 그대로이고 물은 시각만 남는다. 재계산이라 요청을 열 수 있는지 모르면 그때 서버가 확인했다고 본다. 7일도 요청할 때만 지금 설정으로
   * 본다({@link RepairState#atRequest}).
   */
  private RepairState askProgress(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (!askableStatus(now.status()) || now.progressCheckBlocked()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (now.atRequest()) {
      AnswerSupport.checkInterval(now.lastProgressAskAt(), action.createdAt(), progressAskInterval);
    }
    return now.withLastProgressAskAt(action.createdAt());
  }

  /**
   * 진행 상황 남기기(06 REP-6, 03 D): 당사자 누구든, 언제든(처리 완료 뒤에도 덧붙이기, REP-5). 글·사진 하나 이상은 서비스가 pipeline 전에
   * 본다. 덧붙이기라 상태는 그대로다(03 G "덧붙이기"). 거주 종료 뒤에도 정산 완료 전까지 된다(03 D "언제든", #113 3번 답 전까지).
   *
   * <p>[상황이 바뀌었어요]를 켜면(payload {@code asksCheck}, REP-6d) 상대에게 확인을 요청한다. 검사 순서: 행동하는 사람이 공유를 그만뒀으면
   * {@code SHARING_STOPPED}(진행 상황만은 남길 수 있지만 요청은 열지 않음, 03 B) → 하자를 둘 다 확인한 건(맞아요)이 아니거나 지금 요청을 열 수
   * 없으면(거주 종료 뒤, 상대가 공유를 그만둬 이 기록이 보이지 않음) {@code INVALID_STATE} → 한 건에 다시 요청끼리 {@code
   * rerequest.interval}에 한 번(누가 켰든) {@code ASKED_RECENTLY}. 다시 요청끼리는 [보충하고 다시 확인 요청]과 [상황이 바뀌었어요]를
   * 함께 센다(03 G "다시 확인 요청의 한도", 06 0-1 {@code rerequest.interval}, #259 리뷰): 보충하고 7일 안에 상대가 맞아요로 답해도
   * 그 7일이 지나야 켠다. 반대 방향(켠 뒤 보충)은 맞아요가 끝이라 생기지 않아 보충 쪽({@code AnswerSupport.rerequest})은 그대로다. 하자
   * 인정은 그대로다(REP-6d "하자 [맞아요]는 그대로").
   *
   * <p>재계산({@code Replay.fold})이면 열 수 있는지({@code opening})를 모르고, 그때 서버가 확인했다고 본다. 7일 검사도 요청할 때만 지금
   * 설정으로 한다: 설정을 7일에서 14일로 바꾼 뒤 8일 간격으로 기록된 행동을 다시 거절하면 재계산(공유를 그만둔 뒤 상세 조회 등)이 실패한다(#234와 같은 방식,
   * #259 리뷰).
   */
  private RepairState noteProgress(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (!Boolean.TRUE.equals(action.payloadAs(ProgressPayload.class).asksCheck())) {
      return now;
    }
    SituationCheck check = now.situationCheck();
    if (check.opening() == SituationOpening.SHARING_STOPPED) {
      throw new BusinessException(ErrorCode.SHARING_STOPPED);
    }
    if (now.defectAck() != AckState.ACK || check.opening() == SituationOpening.BLOCKED) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    // opening은 켜는 요청일 때만 load가 채운다(재계산이면 null)
    if (check.opening() != null) {
      AnswerSupport.checkInterval(
          latest(check.lastAskedAt(), now.lastRerequestAt()),
          action.createdAt(),
          rerequestInterval);
    }
    return now.withSituationCheck(new SituationCheck(action.createdAt(), check.opening()));
  }

  /** 두 시각 중 늦은 것. 둘 다 없으면 null. */
  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isAfter(b) ? a : b;
  }

  /**
   * 진행 상황 남기기 행동의 payload. 글은 진행 상황 행에만 두고(05 2-3 "payload는 작은 부가 값만") [상황이 바뀌었어요]를 켰는지만 담는다. 켜지
   * 않았으면 키를 두지 않는다(그 전에 남긴 행동과 같은 모양).
   *
   * @param asksCheck [상황이 바뀌었어요]를 켰으면 true. 아니면 null(키 없음)
   */
  public record ProgressPayload(Boolean asksCheck) {

    /** payload의 키. record 칸 이름과 같다({@code ActionEntry.payloadAs}). */
    public static final String ASKS_CHECK = "asksCheck";
  }

  /**
   * 종료에 답하기: 종료 확인 대기면 요청한 측이 아님, 혼자 끝낸 처리 완료면 혼자 끝낸 측이 아님(같은 측이면 {@code NOT_COUNTERPART_SIDE}). 그
   * 밖(처리 완료(양측) 포함)은 {@code INVALID_STATE}.
   */
  private static void requireCloseAnswer(RepairState now, ActionEntry action) {
    requireVisible(now, action);
    requireSide(action);
    if (now.status() == RepairStatus.CLOSE_WAIT) {
      if (action.actorSide() == now.closeRequestedBy()) {
        throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
      }
      return;
    }
    if (!now.closedAlone()) {
      throw new BusinessException(ErrorCode.INVALID_STATE);
    }
    if (closedBySide(now, action.actorSide())) {
      throw new BusinessException(ErrorCode.NOT_COUNTERPART_SIDE);
    }
  }

  /** 혼자 끝낸 측이 이 측인가. */
  private static boolean closedBySide(RepairState now, PartySide side) {
    return side != null && now.closedBy() != null && now.closedBy().name().equals(side.name());
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
