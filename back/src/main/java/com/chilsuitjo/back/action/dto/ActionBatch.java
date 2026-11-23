package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 한 요청 · 한 호실의 행동 목록(09 3-1 "묶는 범위"). 넣은 순서 = {@code room_seq} 순서 = 경과에 보이는 순서.
 *
 * <pre>{@code
 * // 처음 초대 [동의하고 보여 주기]
 * pipeline.executeAll(ActionBatch.of(roomRecordId, Actor.member(memberId), idempotencyKey)
 *     .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", req.consentVersion()))
 *     .step(ActionType.INVITE, done -> ActionTarget.party(partyId), partyStateStore, partyTransitions));
 * }</pre>
 */
public final class ActionBatch {

  private final UUID roomRecordId;
  private final Actor actor;
  private final String idempotencyKey;
  private final boolean newRoom;
  private final List<ActionStep<?>> steps = new ArrayList<>();
  private Runnable beforeReplayCheck = () -> {};

  private ActionBatch(UUID roomRecordId, Actor actor, String idempotencyKey, boolean newRoom) {
    this.roomRecordId = roomRecordId;
    this.actor = Objects.requireNonNull(actor, "actor");
    this.idempotencyKey = checkKey(idempotencyKey);
    this.newRoom = newRoom;
  }

  /**
   * 화면 키 길이 한도. 화면의 키는 UUID(36자)이고, pipeline이 {@code #2} …를 붙여도 V1 {@code varchar(100)}을 넘지 않게 여유를
   * 둔다.
   */
  public static final int MAX_KEY_LENGTH = 64;

  /**
   * 화면이 보낸 {@code Idempotency-Key}를 확인한다(06 4-0). 비었거나 {@code #}가 들었거나({@code <키>#2} …는 pipeline이
   * 붙임) {@link #MAX_KEY_LENGTH}를 넘으면 {@code VALIDATION_FAILED}(400). 서비스가 batch를 만들 때 걸리므로 트랜잭션·잠금
   * 전에 끝난다.
   */
  private static String checkKey(String key) {
    if (key == null || key.isBlank() || key.indexOf('#') >= 0 || key.length() > MAX_KEY_LENGTH) {
      throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    return key;
  }

  public static ActionBatch of(UUID roomRecordId, Actor actor, String idempotencyKey) {
    return new ActionBatch(
        Objects.requireNonNull(roomRecordId, "roomRecordId"), actor, idempotencyKey, false);
  }

  /**
   * 호실을 만드는 요청({@code CREATE_ROOM})만. 아직 호실 행이 없어 잠금·호실 단위 거절을 건너뛴다. 첫 단계의 {@code save}가 새 호실을
   * {@code SavedState.target}({@code ActionTarget.roomRecord})으로 돌려주면 그 호실의 첫 행동({@code room_seq}
   * 1)으로 남는다.
   *
   * <p>아직 {@code RoomGate}가 없어서 회원의 측을 pipeline이 채우지 못한다. 측이 필요하면 {@code
   * Actor.member(id).withSide(side)}로 넘긴다.
   */
  public static ActionBatch newRoom(Actor actor, String idempotencyKey) {
    return new ActionBatch(null, actor, idempotencyKey, true);
  }

  /**
   * 행동 하나짜리. {@code ActionPipeline.execute}가 쓴다. 호실 ID가 꼭 있어야 한다(호실 ID를 빠뜨린 호출이 잠금과 호실 단위 거절을 건너뛰지
   * 않게). 호실을 만드는 요청은 {@link #newRoom}으로 연다.
   */
  public static <S> ActionBatch single(
      ActionCommand command, StateStore<S> store, Transitions<S> rules) {
    ActionTarget target = command.target();
    return single(command, ActionStep.of(command.type(), done -> target, store, rules));
  }

  /** 새 기록을 만드는 행동 하나짜리({@code ActionPipeline.create}). {@code command.target()}은 비어 있어야 한다. */
  public static <S> ActionBatch singleCreating(
      ActionCommand command, StateStore<S> store, Transitions<S> rules) {
    if (command.target() != null) {
      throw new IllegalArgumentException("새 기록을 만드는 행동에는 대상을 주지 않아요");
    }
    return single(command, ActionStep.creating(command.type(), store, rules));
  }

  private static ActionBatch single(ActionCommand command, ActionStep<?> step) {
    if (command.roomRecordId() == null) {
      throw new IllegalArgumentException("호실 ID가 없어요. 호실을 만드는 요청은 ActionBatch.newRoom으로 열어 주세요");
    }
    ActionBatch batch = of(command.roomRecordId(), command.actor(), command.idempotencyKey());
    return batch.step(
        step.withSeenVersionNo(command.seenVersionNo())
            .withContent(command.content())
            .withReason(command.reason())
            .withPayload(command.payload()));
  }

  /** 상태 있는 행동. 대상은 앞 단계 결과로 정할 수 있다(호실 자체면 {@code done -> null}). */
  public <S> ActionBatch step(
      ActionType type,
      Function<List<ActionResult>, ActionTarget> target,
      StateStore<S> store,
      Transitions<S> rules) {
    return step(ActionStep.of(type, target, store, rules));
  }

  /** 새 기록을 만드는 행동({@code REGISTER} 등). 대상은 {@code save}가 돌려준다. */
  public <S> ActionBatch creating(ActionType type, StateStore<S> store, Transitions<S> rules) {
    return step(ActionStep.creating(type, store, rules));
  }

  /** {@code seenVersionNo}·{@code content}·{@code reason}·{@code payload}·{@code when}까지 줄 때. */
  public <S> ActionBatch step(ActionStep<S> step) {
    steps.add(Objects.requireNonNull(step, "step"));
    return this;
  }

  /** 상태 칸이 없는 행동({@code SHARE_CONSENT} 등). 행동 기록만 남긴다. */
  public ActionBatch record(ActionType type, Map<String, Object> payload) {
    return step(ActionStep.record(type, payload));
  }

  /**
   * 상태 칸이 없지만 대상이 있는 행동. 예: {@code .record(LINK_VIEW, done -> ActionTarget.of(TargetKind.LINK,
   * linkId), Map.of())}. 대상은 같은 호실이어야 한다(행동 기록의 복합 FK, 05 0장).
   */
  public ActionBatch record(
      ActionType type,
      Function<List<ActionResult>, ActionTarget> target,
      Map<String, Object> payload) {
    return step(ActionStep.record(type, target, payload));
  }

  /**
   * 호실 잠금을 얻은 뒤 멱등 조회보다 먼저 실행할 현재 접근 검증. 처음 실행과 저장된 결과의 replay 모두에 적용한다. 검증은 pipeline 트랜잭션 안에서
   * 부르므로, 도메인 행 잠금이 필요하면 호실 다음 순서로 얻는다.
   */
  public ActionBatch beforeReplayCheck(Runnable check) {
    if (newRoom) {
      throw new IllegalStateException("호실을 만드는 요청에는 접근 검증을 붙일 수 없어요");
    }
    this.beforeReplayCheck = Objects.requireNonNull(check, "check");
    return this;
  }

  /** ActionPipeline이 호실 잠금 뒤, 멱등 조회 전에 부른다. */
  public void runBeforeReplayCheck() {
    beforeReplayCheck.run();
  }

  public UUID roomRecordId() {
    return roomRecordId;
  }

  public Actor actor() {
    return actor;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public boolean isNewRoom() {
    return newRoom;
  }

  public List<ActionStep<?>> steps() {
    return Collections.unmodifiableList(steps);
  }
}
