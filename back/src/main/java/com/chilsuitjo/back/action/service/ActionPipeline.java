package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionResult;
import com.chilsuitjo.back.action.dto.ActionStep;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.BatchResult;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ActionType.Allow;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.common.Uuids;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 행동 기록을 남기는 모든 요청이 지나는 틀. 순서·잠금·멱등·호실 단위 거절·트랜잭션은 여기에만 있고, 각 도메인은 {@link StateStore}와 {@link
 * Transitions}만 채운다. 기준은 docs/rules/09-프로젝트-구조.md 3-1.
 *
 * <pre>
 * 1 잠금 + RoomGate 읽기(newRoom이면 건너뜀) → 현재 접근 검증(설정된 경우) → 2 멱등(같은 키면 처음 결과를 돌려주고 끝)
 * 단계마다: 3 호실 단위 거절 → 4 대상 정하기 → 5 load → (when이 거짓이면 건너뜀) → 6 apply → 7 save → 8 기록 → 9 딸린 행동 기록
 * </pre>
 *
 * <p><b>트랜잭션 경계는 pipeline이다.</b> 부르는 쪽은 {@code @Transactional}을 두지 않는다. 같은 키가 동시에 와 제약에 걸리면 되돌린 뒤 새
 * 트랜잭션에서 처음 결과를 찾아 {@code replayed}로 돌려준다(06 4-0).
 *
 * <p>여기에 특정 행동·도메인을 위한 분기를 넣지 않는다. 조건은 부르는 쪽이 {@code ActionStep.when}으로 넘기고, 도중에 생기는 일은 {@link
 * ActionFollowUps}로 남긴다. {@code save}가 이 요청의 행동 시각을 읽을 때는 {@link ActionRequestContext}를 쓴다.
 */
@Service
public class ActionPipeline {

  private final RoomGateReader roomGateReader;
  private final ActionWriter actionWriter;
  private final ActionFollowUps followUps;
  private final ActionRequestContext requestContext;
  private final TransactionTemplate transaction;
  private final Clock clock;

  ActionPipeline(
      RoomGateReader roomGateReader,
      ActionWriter actionWriter,
      ActionFollowUps followUps,
      ActionRequestContext requestContext,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.roomGateReader = roomGateReader;
    this.actionWriter = actionWriter;
    this.followUps = followUps;
    this.requestContext = requestContext;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** 한 요청의 행동들(09 3-1). */
  public BatchResult executeAll(ActionBatch batch) {
    return runInBoundary(batch).result();
  }

  /** 행동 하나짜리 바로가기. 다시 온 요청이면 {@code state}는 다시 읽은 지금 상태다. */
  @SuppressWarnings("unchecked")
  public <S> PipelineResult<S> execute(
      ActionCommand command, StateStore<S> store, Transitions<S> rules) {
    Outcome outcome = runInBoundary(ActionBatch.single(command, store, rules));
    return new PipelineResult<>((S) outcome.lastState(), outcome.result().first());
  }

  /** 새 기록을 만드는 행동 하나짜리({@code REGISTER} 등). 대상은 {@code save}가 돌려주고, 결과의 대상이 새 기록이다. */
  @SuppressWarnings("unchecked")
  public <S> PipelineResult<S> create(
      ActionCommand command, StateStore<S> store, Transitions<S> rules) {
    Outcome outcome = runInBoundary(ActionBatch.singleCreating(command, store, rules));
    return new PipelineResult<>((S) outcome.lastState(), outcome.result().first());
  }

  private Outcome runInBoundary(ActionBatch batch) {
    if (batch.steps().isEmpty()) {
      throw new IllegalArgumentException("행동이 하나도 없는 요청이에요");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "pipeline 바깥에 트랜잭션을 두지 않아요(09 3-1 트랜잭션 경계). 서비스의 @Transactional을 빼 주세요");
    }
    try {
      return Objects.requireNonNull(transaction.execute(status -> run(batch)));
    } catch (DataIntegrityViolationException conflict) {
      // 같은 키가 동시에 왔다: 먼저 끝난 요청의 결과를 새 트랜잭션에서 찾는다(06 4-0).
      Outcome replay = transaction.execute(status -> replayIfDone(batch));
      if (replay == null) {
        throw conflict;
      }
      return replay;
    }
  }

  /**
   * 충돌 뒤 새 트랜잭션에서 처음 결과를 찾는다. 처음 실행과 같이 호실을 먼저 잠근다: 다시 읽는 {@code load}가 잠금 없이 돌면 그사이 바뀐 상태와 섞이고, 잠금
   * 순서(호실 먼저)도 어긋난다(#94 리뷰, 링크 열람 경합). 호실을 만드는 요청은 아직 잠글 호실을 모른다.
   */
  private Outcome replayIfDone(ActionBatch batch) {
    if (!batch.isNewRoom()) {
      roomGateReader.lockAndRead(batch.roomRecordId(), batch.actor()); // 1
    }
    batch.runBeforeReplayCheck();
    List<ActionEntry> done = actionWriter.findRequest(batch.actor().key(), batch.idempotencyKey());
    return done.isEmpty() ? null : replay(batch, done);
  }

  private Outcome run(ActionBatch batch) {
    UUID roomRecordId = batch.roomRecordId();
    RoomGate gate =
        batch.isNewRoom() ? null : roomGateReader.lockAndRead(roomRecordId, batch.actor()); // 1

    batch.runBeforeReplayCheck();
    List<ActionEntry> done =
        actionWriter.findRequest(batch.actor().key(), batch.idempotencyKey()); // 2
    if (!done.isEmpty()) {
      return replay(batch, done);
    }

    Actor actor =
        gate != null && batch.actor().isMember()
            ? batch.actor().withSide(gate.side())
            : batch.actor();
    // 한 요청의 행동을 묶는 값(#66). 시간순으로 정렬되도록 UUID v7을 쓴다.
    UUID requestId = Uuids.v7();
    // 한 요청의 서버 시각은 하나다. apply에 넘긴 값 그대로 저장·해시에 쓴다(05 2-3 「해시 입력 형식」 시각, 09 3-1 순서 8. DB 정밀도에 맞춰
    // 마이크로초).
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
    List<ActionResult> results = new ArrayList<>();
    Object lastState = null;
    int written = 0;

    for (ActionStep<?> step : batch.steps()) {
      checkRoom(gate, actor, step.type()); // 3
      ActionTarget target = step.target().apply(Collections.unmodifiableList(results)); // 4
      ActionCommand command =
          new ActionCommand(
              roomRecordId,
              actor,
              step.type(),
              keyOf(batch.idempotencyKey(), written + 1),
              target,
              step.seenVersionNo(),
              step.content(),
              step.reason(),
              step.payload());

      if (step.recordOnly()) {
        results.add(
            resultOf(
                actionWriter.write(
                    requireRoom(command.withTarget(withoutRoomTarget(command.target()))),
                    requestId,
                    at),
                false)); // 8
        written++;
        continue;
      }

      StepOutcome stepOutcome = runStep(step, command, gate, requestId, at, written);
      if (stepOutcome == null) {
        continue; // when이 거짓: 기록 없이 건너뜀
      }
      if (roomRecordId == null) {
        roomRecordId = stepOutcome.roomRecordId();
      }
      results.addAll(stepOutcome.results());
      written += stepOutcome.results().size();
      lastState = stepOutcome.state();
    }

    // 모든 단계가 when으로 건너뛰어졌으면 남긴 행동 없이 끝난다(예: 정산이 이미 있어 OPEN_SETTLEMENT가 필요 없음).
    return new Outcome(new BatchResult(results, false), lastState);
  }

  /** 5~9. {@code when}이 거짓이면 null. */
  private <S> StepOutcome runStep(
      ActionStep<S> step,
      ActionCommand command,
      RoomGate gate,
      UUID requestId,
      Instant at,
      int written) {
    S now = step.store().load(command); // 5
    if (!step.condition().test(now)) {
      return null;
    }
    followUps.begin();
    try {
      S next = step.rules().apply(now, preview(command, at)); // 6
      SavedState saved = save(step.store(), command, next, requestId, at); // 7
      checkCreated(step, command, saved);

      ActionCommand toWrite = command;
      ActionTarget resultTarget = null;
      if (saved.target() != null && saved.target().kind() == TargetKind.ROOM_RECORD) {
        if (command.roomRecordId() == null) {
          // newRoom의 첫 단계: 새 호실의 첫 행동. 행동 기록에는 대상 칸 없이 남는다.
          toWrite = toWrite.withRoomRecordId(saved.target().id());
          resultTarget = saved.target();
        } else if (!saved.target().id().equals(command.roomRecordId())) {
          throw new IllegalStateException("save가 이 요청과 다른 호실을 돌려줬어요");
        }
      } else if (saved.target() != null) {
        toWrite = toWrite.withTarget(saved.target());
      }
      if (saved.content() != null) {
        toWrite = toWrite.withContent(saved.content());
      }
      toWrite = toWrite.withExtraPayload(saved.payload()); // 붙인 사진 목록 등(#141)
      ActionCommand recorded = requireRoom(toWrite.withTarget(withoutRoomTarget(toWrite.target())));

      List<ActionResult> results = new ArrayList<>();
      ActionResult main = resultOf(actionWriter.write(recorded, requestId, at), false); // 8
      results.add(resultTarget == null ? main : withTarget(main, resultTarget));

      int n = written + 1;
      for (ActionFollowUps.FollowUp followUp : followUps.drain()) { // 9
        checkRoom(gate, followUp.actor(), followUp.type());
        n++;
        ActionCommand extra =
            new ActionCommand(
                recorded.roomRecordId(),
                followUp.actor(),
                followUp.type(),
                followUpKey(requestId, n),
                withoutRoomTarget(followUp.target()),
                null,
                null,
                null,
                followUp.payload());
        results.add(resultOf(actionWriter.write(extra, requestId, at), false));
      }
      return new StepOutcome(results, next, recorded.roomRecordId());
    } finally {
      followUps.end();
    }
  }

  /**
   * 7. 요청 시각 창구({@link ActionRequestContext})는 {@code save}(와 그 안에서 동기로 도는 리스너)에서만 연다. {@code
   * load}·{@code when}은 다시 온 요청(창구를 열지 않음)에서도 불리고, 시각에 따른 판단은 행동 기록에 남도록 {@code apply}가 {@code
   * createdAt}으로 한다(REP-10). 그래서 그 자리에서 읽으면 처음 요청부터 바로 실패한다(#94 리뷰).
   */
  private <S> SavedState save(
      StateStore<S> store, ActionCommand command, S next, UUID requestId, Instant at) {
    requestContext.begin(requestId, at);
    try {
      return store.save(command, next);
    } finally {
      requestContext.end();
    }
  }

  /**
   * 3. 호실 단위 거절. 서버·운영 행동과 호실을 만드는 요청은 건너뛴다. 여럿이 겹치면 정산 완료 → 거주 종료 → 합류 전 기록 → 공유 중단 순서로 앞의 것을
   * 돌려준다(정산 완료면 거주 종료도 참이라 {@code SETTLED}가 먼저).
   */
  private static void checkRoom(RoomGate gate, Actor actor, ActionType type) {
    if (gate == null || actor.isSystem()) {
      return;
    }
    if (gate.settled() && !type.allows(Allow.AFTER_SETTLED)) {
      throw new BusinessException(ErrorCode.SETTLED);
    }
    if (gate.ended() && type.blockedAfterEnded()) {
      throw new BusinessException(ErrorCode.ROOM_ENDED);
    }
    if (gate.joinedAway() && !type.allows(Allow.ON_JOINED)) {
      throw new BusinessException(ErrorCode.JOINED_RECORD);
    }
    if (gate.mySharingStopped() && !type.allows(Allow.WHILE_SHARING_STOPPED)) {
      throw new BusinessException(ErrorCode.SHARING_STOPPED);
    }
  }

  /**
   * 2. 같은 키로 다시 온 요청. 처음 요청과 다르면 화면 버그라 거절한다(06 4-0 "같은 키에 다른 대상·다른 행동").
   *
   * <p>같은 요청이란: 같은 호실이고, 요청 주체가 남긴 행동({@code <키>}, {@code <키>#n})이 이번 단계들과 <b>순서대로</b> 짝이 맞는 것이다.
   * {@code when}으로 건너뛸 수 있는 단계는 짝이 없어도 되고, 짝이 맞은 단계는 대상도 같아야 한다. 딸린 행동은 그 단계에서 생긴 것이라 비교하지 않는다.
   *
   * <p>한계: 처음 요청에서 건너뛴 단계는 기록이 없어서, 단계 구성이 조건부 단계만큼 다른 요청은 가리지 못한다. 예를 들어 {@code [INVITE]}로 끝난 키에
   * {@code [SHARE_CONSENT(when), INVITE]}가 오면 같은 요청으로 본다. 단계 구성은 클라이언트가 아니라 서버 코드가 버튼마다 정하므로 다른 버튼이
   * 같은 키를 쓴 경우(화면 버그)에만 생기고, 그때도 돌려주는 결과는 저장된 행동과 종류·대상·순서가 같다.
   */
  private Outcome replay(ActionBatch batch, List<ActionEntry> done) {
    ActionStep<?> firstStep = batch.steps().getFirst();
    if (!batch.isNewRoom() && !batch.roomRecordId().equals(done.getFirst().roomRecordId())) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    List<Integer> own = new ArrayList<>();
    for (int i = 0; i < done.size(); i++) {
      if (isOwnKey(done.get(i).idempotencyKey(), batch.idempotencyKey())) {
        own.add(i);
      }
    }
    if (!matches(batch.steps(), 0, done, own, 0)) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    List<ActionResult> results = done.stream().map(entry -> resultOf(entry, true)).toList();
    Object state = null;
    if (batch.steps().size() == 1 && !firstStep.recordOnly()) {
      state = reload(firstStep, batch, results.getFirst());
    }
    return new Outcome(new BatchResult(results, true), state);
  }

  private static <S> S reload(ActionStep<S> step, ActionBatch batch, ActionResult result) {
    // 처음 실행과 같은 모양으로 읽는다: 호실 기록 자체가 대상이면 대상은 null이고 호실 ID만 둔다.
    ActionTarget target = result.target();
    UUID roomRecordId = batch.roomRecordId();
    if (target != null && target.kind() == TargetKind.ROOM_RECORD) {
      roomRecordId = target.id();
      target = null;
    }
    return step.store()
        .load(
            new ActionCommand(
                roomRecordId,
                batch.actor(),
                step.type(),
                batch.idempotencyKey(),
                target,
                step.seenVersionNo(),
                step.content(),
                step.reason(),
                step.payload()));
  }

  private static boolean isOwnKey(String key, String baseKey) {
    return key.equals(baseKey) || key.startsWith(baseKey + "#");
  }

  /**
   * 단계 {@code i}부터와 요청 주체의 행동 {@code own[j]}부터가 순서대로 짝이 맞는지. 건너뛸 수 있는 단계는 짝 없이 넘어가 볼 수 있어서(같은 종류가
   * 이어질 때) 되짚어 본다. 한 요청의 단계는 몇 개뿐이라 비용은 작다.
   */
  private static boolean matches(
      List<ActionStep<?>> steps, int i, List<ActionEntry> done, List<Integer> own, int j) {
    if (i == steps.size()) {
      return j == own.size();
    }
    ActionStep<?> step = steps.get(i);
    if (j < own.size()
        && sameAction(step, done, own.get(j))
        && matches(steps, i + 1, done, own, j + 1)) {
      return true;
    }
    return step.optional() && matches(steps, i + 1, done, own, j);
  }

  /**
   * 단계와 남은 행동이 같은 종류·같은 대상인지. 대상은 처음 실행 때처럼 그 행동 앞의 결과로 다시 정해 본다.
   *
   * <ul>
   *   <li>새 기록을 만드는 단계({@link ActionStep#creates()}): 물은 대상이 없고, 저장된 대상은 그때 만든 기록이라 종류만 본다
   *   <li>그 밖의 단계: 다시 정한 대상이 저장된 대상과 같아야 한다. 호실 자체가 대상이면 둘 다 없다
   *   <li>대상을 다시 정하다 실패하면 다른 요청이다. 같은 요청이면 처음과 같은 앞 결과로 정하므로 실패할 수 없다
   * </ul>
   */
  private static boolean sameAction(ActionStep<?> step, List<ActionEntry> done, int index) {
    ActionEntry entry = done.get(index);
    if (step.type() != entry.type()) {
      return false;
    }
    if (step.creates()) {
      return true;
    }
    List<ActionResult> before =
        done.subList(0, index).stream().map(e -> resultOf(e, true)).toList();
    ActionTarget asked;
    try {
      asked = withoutRoomTarget(step.target().apply(before));
    } catch (RuntimeException cannotResolve) {
      return false;
    }
    return Objects.equals(asked, entry.target());
  }

  /**
   * 새 기록을 만드는 단계인지 아닌지가 {@code save} 결과와 맞는지. 이 표시가 맞아야 다시 온 요청에서 "물은 대상이 없음"을 바르게 가린다. 호실을 만드는 첫
   * 단계({@code CREATE_ROOM})는 새 호실을 {@code ROOM_RECORD}로 돌려주므로 여기서 보지 않는다.
   */
  private static void checkCreated(ActionStep<?> step, ActionCommand command, SavedState saved) {
    ActionTarget created = saved.target();
    boolean madeRecord = created != null && created.kind() != TargetKind.ROOM_RECORD;
    if (step.creates() && !madeRecord) {
      throw new IllegalStateException(step.type() + ": 새 기록을 만드는 단계인데 save가 만든 기록을 돌려주지 않았어요");
    }
    if (!step.creates() && command.target() == null && madeRecord) {
      throw new IllegalStateException(
          step.type() + ": 새 기록을 만드는 행동은 ActionStep.creating·ActionPipeline.create로 열어 주세요");
    }
    // 물어본 대상이 있는데 다른 기록을 돌려주면, 행동 기록의 대상이 바뀌어 같은 요청이 다시 올 때
    // 물어본 대상과 비교가 어긋난다(정상 재시도가 IDEMPOTENCY_KEY_REUSED). 부모를 주고 자식을 만드는 모양을 막는다.
    if (command.target() != null && madeRecord && !created.equals(command.target())) {
      throw new IllegalStateException(
          step.type()
              + ": save가 물어본 대상("
              + command.target()
              + ")과 다른 기록을 돌려줬어요. 새 기록은 ActionStep.creating으로 열어 주세요");
    }
  }

  /** apply에 넘기는 저장 전 행동. id·roomSeq·지문은 저장할 때 정해지고, 시각은 저장할 값과 같은 {@code at}이다. */
  private static ActionEntry preview(ActionCommand c, Instant at) {
    return new ActionEntry(
        null,
        c.roomRecordId(),
        0,
        c.type(),
        c.actor().side(),
        c.actor().key(),
        null,
        null,
        c.target(),
        c.seenVersionNo(),
        null,
        c.reason(),
        c.payload(),
        at);
  }

  /** 호실 기록 자체가 대상인 행동은 결과에서 ROOM_RECORD로 돌려준다(다시 온 요청에서도 만든 호실 ID를 알 수 있게). */
  private static ActionResult resultOf(ActionEntry entry, boolean replayed) {
    ActionTarget target =
        entry.target() != null ? entry.target() : ActionTarget.roomRecord(entry.roomRecordId());
    return new ActionResult(entry.id(), entry.roomSeq(), target, replayed);
  }

  private static ActionResult withTarget(ActionResult result, ActionTarget target) {
    return new ActionResult(result.actionId(), result.roomSeq(), target, result.replayed());
  }

  private static ActionCommand requireRoom(ActionCommand command) {
    if (command.roomRecordId() == null) {
      throw new IllegalStateException(
          "호실을 만드는 요청의 첫 단계는 save가 새 호실(ActionTarget.roomRecord)을 돌려줘야 해요");
    }
    return command;
  }

  private static String keyOf(String baseKey, int n) {
    return n == 1 ? baseKey : baseKey + "#" + n;
  }

  /**
   * 딸린 행동의 키. 주체가 요청과 다를 수 있어(대개 {@code server}) 요청 키 대신 {@code request_id}를 쓴다. 두 회원이 같은 키를 써도
   * {@code UNIQUE(actor_key, idempotency_key)}에서 서로 부딪히지 않는다(#66).
   */
  private static String followUpKey(UUID requestId, int n) {
    return requestId + "#" + n;
  }

  /** 행동 기록에는 호실 기록 자체를 대상 칸 없이 남긴다(05 2-3 "0이면 호실 기록 자체"). */
  private static ActionTarget withoutRoomTarget(ActionTarget target) {
    return target != null && target.kind() == TargetKind.ROOM_RECORD ? null : target;
  }

  private record Outcome(BatchResult result, Object lastState) {}

  private record StepOutcome(List<ActionResult> results, Object state, UUID roomRecordId) {}
}
