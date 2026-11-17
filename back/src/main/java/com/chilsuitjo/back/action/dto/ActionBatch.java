package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.action.service.StateStore;
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

  private ActionBatch(UUID roomRecordId, Actor actor, String idempotencyKey, boolean newRoom) {
    this.roomRecordId = roomRecordId;
    this.actor = Objects.requireNonNull(actor, "actor");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank() || idempotencyKey.indexOf('#') >= 0) {
      // <키>#2 …는 pipeline이 붙인다. 화면의 키는 UUID(06 4-0)라 #가 없다.
      throw new IllegalArgumentException("Idempotency-Key가 비었거나 #가 들어 있어요");
    }
    this.newRoom = newRoom;
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
    if (command.roomRecordId() == null) {
      throw new IllegalArgumentException("호실 ID가 없어요. 호실을 만드는 요청은 ActionBatch.newRoom으로 열어 주세요");
    }
    ActionBatch batch = of(command.roomRecordId(), command.actor(), command.idempotencyKey());
    ActionTarget target = command.target();
    return batch.step(
        ActionStep.of(command.type(), done -> target, store, rules)
            .withSeenVersionNo(command.seenVersionNo())
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

  /** {@code seenVersionNo}·{@code content}·{@code reason}·{@code payload}·{@code when}까지 줄 때. */
  public <S> ActionBatch step(ActionStep<S> step) {
    steps.add(Objects.requireNonNull(step, "step"));
    return this;
  }

  /** 상태 칸이 없는 행동({@code SHARE_CONSENT} 등). 행동 기록만 남긴다. */
  public ActionBatch record(ActionType type, Map<String, Object> payload) {
    return step(ActionStep.record(type, payload));
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
