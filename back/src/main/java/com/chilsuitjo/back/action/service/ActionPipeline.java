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
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
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
 * 1 잠금 + RoomGate 읽기(newRoom이면 건너뜀) → 2 멱등(같은 키면 처음 결과를 돌려주고 끝)
 * 단계마다: 3 호실 단위 거절 → 4 대상 정하기 → 5 load → (when이 거짓이면 건너뜀) → 6 apply → 7 save → 8 기록 → 9 딸린 행동 기록
 * </pre>
 *
 * <p><b>트랜잭션 경계는 pipeline이다.</b> 부르는 쪽은 {@code @Transactional}을 두지 않는다. 같은 키가 동시에 와 제약에 걸리면 되돌린 뒤 새
 * 트랜잭션에서 처음 결과를 찾아 {@code replayed}로 돌려준다(06 4-0).
 *
 * <p>여기에 특정 행동·도메인을 위한 분기를 넣지 않는다. 조건은 부르는 쪽이 {@code ActionStep.when}으로 넘기고, 도중에 생기는 일은 {@link
 * ActionFollowUps}로 남긴다.
 */
@Service
public class ActionPipeline {

  private final RoomGateReader roomGateReader;
  private final ActionWriter actionWriter;
  private final ActionFollowUps followUps;
  private final TransactionTemplate transaction;
  private final Clock clock;

  ActionPipeline(
      RoomGateReader roomGateReader,
      ActionWriter actionWriter,
      ActionFollowUps followUps,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.roomGateReader = roomGateReader;
    this.actionWriter = actionWriter;
    this.followUps = followUps;
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

  private Outcome replayIfDone(ActionBatch batch) {
    List<ActionEntry> done = actionWriter.findRequest(batch.actor().key(), batch.idempotencyKey());
    return done.isEmpty() ? null : replay(batch, done);
  }

  private Outcome run(ActionBatch batch) {
    UUID roomRecordId = batch.roomRecordId();
    RoomGate gate =
        batch.isNewRoom() ? null : roomGateReader.lockAndRead(roomRecordId, batch.actor()); // 1

    List<ActionEntry> done =
        actionWriter.findRequest(batch.actor().key(), batch.idempotencyKey()); // 2
    if (!done.isEmpty()) {
      return replay(batch, done);
    }

    Actor actor =
        gate != null && batch.actor().isMember()
            ? batch.actor().withSide(gate.side())
            : batch.actor();
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
                    requireRoom(command.withTarget(withoutRoomTarget(command.target())))),
                false)); // 8
        written++;
        continue;
      }

      StepOutcome stepOutcome = runStep(step, command, gate, batch.idempotencyKey(), written);
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
      ActionStep<S> step, ActionCommand command, RoomGate gate, String baseKey, int written) {
    S now = step.store().load(command); // 5
    if (!step.condition().test(now)) {
      return null;
    }
    followUps.begin();
    try {
      S next = step.rules().apply(now, preview(command)); // 6
      SavedState saved = step.store().save(command, next); // 7

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
      ActionCommand recorded = requireRoom(toWrite.withTarget(withoutRoomTarget(toWrite.target())));

      List<ActionResult> results = new ArrayList<>();
      ActionResult main = resultOf(actionWriter.write(recorded), false); // 8
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
                keyOf(baseKey, n),
                withoutRoomTarget(followUp.target()),
                null,
                null,
                null,
                followUp.payload());
        results.add(resultOf(actionWriter.write(extra), false));
      }
      return new StepOutcome(results, next, recorded.roomRecordId());
    } finally {
      followUps.end();
    }
  }

  /** 3. 서버·운영 행동과 호실을 만드는 요청은 건너뛴다. */
  private static void checkRoom(RoomGate gate, Actor actor, ActionType type) {
    if (gate == null || actor.isSystem()) {
      return;
    }
    if (gate.settled() && !type.allows(Allow.AFTER_SETTLED)) {
      throw new BusinessException(ErrorCode.SETTLED);
    }
    if (gate.joinedAway() && !type.allows(Allow.ON_JOINED)) {
      throw new BusinessException(ErrorCode.JOINED_RECORD);
    }
    if (gate.mySharingStopped() && type.blockedWhileSharingStopped()) {
      throw new BusinessException(ErrorCode.SHARING_STOPPED);
    }
  }

  /** 2. 같은 키로 다시 온 요청. 같은 키에 다른 행동이면 화면 버그라 거절한다(06 4-0). */
  private Outcome replay(ActionBatch batch, List<ActionEntry> done) {
    ActionStep<?> firstStep = batch.steps().getFirst();
    ActionEntry firstDone = done.getFirst();
    // 첫 단계가 when으로 건너뛰어졌을 수 있어서, 처음 남은 행동이 이 요청의 단계 중 하나인지 본다.
    boolean sameRequest = batch.steps().stream().anyMatch(step -> step.type() == firstDone.type());
    if (!sameRequest) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    if (firstStep.type() == firstDone.type()) {
      ActionTarget asked = targetWithoutResults(firstStep);
      if (asked != null && firstDone.target() != null && !asked.equals(firstDone.target())) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
      }
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

  private static ActionTarget targetWithoutResults(ActionStep<?> step) {
    try {
      return step.target().apply(List.of());
    } catch (RuntimeException needsEarlierResults) {
      return null;
    }
  }

  /** apply에 넘기는 저장 전 행동. id·roomSeq·지문은 저장할 때 정해진다. */
  private ActionEntry preview(ActionCommand c) {
    return new ActionEntry(
        null,
        c.roomRecordId(),
        0,
        c.type(),
        c.actor().side(),
        c.actor().key(),
        c.target(),
        c.seenVersionNo(),
        null,
        c.reason(),
        c.payload(),
        clock.instant());
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

  /** 행동 기록에는 호실 기록 자체를 대상 칸 없이 남긴다(05 2-3 "0이면 호실 기록 자체"). */
  private static ActionTarget withoutRoomTarget(ActionTarget target) {
    return target != null && target.kind() == TargetKind.ROOM_RECORD ? null : target;
  }

  private record Outcome(BatchResult result, Object lastState) {}

  private record StepOutcome(List<ActionResult> results, Object state, UUID roomRecordId) {}
}
