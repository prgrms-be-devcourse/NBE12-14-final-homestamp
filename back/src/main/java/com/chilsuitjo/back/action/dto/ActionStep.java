package com.chilsuitjo.back.action.dto;

import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.action.service.StateStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 한 요청 안의 행동 한 단계(09 3-1).
 *
 * @param target 앞 단계 결과로 이 단계의 대상을 정한다. 호실 자체거나 새로 만드는 행동이면 {@code done -> null}
 * @param store 상태 칸이 없는 행동({@link #record})이면 null
 * @param rules 상태 칸이 없는 행동이면 null
 * @param condition 잠근 뒤 읽은 지금 상태로 이 단계를 할지 정한다. 거짓이면 기록 없이 건너뛴다. 조건은 부르는 쪽이 정한다(pipeline에 행동별 분기를
 *     넣지 않는다)
 * @param <S> 그 도메인의 상태 값
 */
public record ActionStep<S>(
    ActionType type,
    Function<List<ActionResult>, ActionTarget> target,
    Integer seenVersionNo,
    DigestInput content,
    String reason,
    Map<String, Object> payload,
    StateStore<S> store,
    Transitions<S> rules,
    Predicate<S> condition) {

  /** {@code when}을 주지 않은 단계의 조건. 이 값이면 늘 실행되는 단계다(다시 온 요청 비교에서 건너뛸 수 없음). */
  private static final Predicate<Object> ALWAYS = state -> true;

  @SuppressWarnings("unchecked")
  public ActionStep {
    Objects.requireNonNull(type, "type");
    target = target == null ? done -> null : target;
    payload = payload == null ? Map.of() : Map.copyOf(payload);
    condition = condition == null ? (Predicate<S>) (Predicate<?>) ALWAYS : condition;
    if ((store == null) != (rules == null)) {
      throw new IllegalArgumentException("store와 rules는 함께 주거나 함께 비워요");
    }
  }

  /** 상태 있는 행동. */
  public static <S> ActionStep<S> of(
      ActionType type,
      Function<List<ActionResult>, ActionTarget> target,
      StateStore<S> store,
      Transitions<S> rules) {
    return new ActionStep<>(
        type,
        target,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(store, "store"),
        Objects.requireNonNull(rules, "rules"),
        null);
  }

  /** 상태 칸이 없는 행동({@code SHARE_CONSENT} 등). 행동 기록만 남긴다. */
  public static ActionStep<Void> record(ActionType type, Map<String, Object> payload) {
    return new ActionStep<>(type, null, null, null, null, payload, null, null, null);
  }

  public boolean recordOnly() {
    return store == null;
  }

  /** {@code when}으로 건너뛸 수 있는 단계인지. 다시 온 요청을 비교할 때 기록이 없어도 되는 단계다. */
  public boolean optional() {
    return condition != ALWAYS;
  }

  /** 지금 상태가 조건에 맞을 때만 이 단계를 한다(예: 정산이 없을 때만 {@code OPEN_SETTLEMENT}). */
  public ActionStep<S> when(Predicate<S> newCondition) {
    if (recordOnly()) {
      throw new IllegalStateException("상태 칸이 없는 행동에는 조건을 걸 수 없어요");
    }
    return new ActionStep<>(
        type, target, seenVersionNo, content, reason, payload, store, rules, newCondition);
  }

  public ActionStep<S> withSeenVersionNo(Integer newSeenVersionNo) {
    return new ActionStep<>(
        type, target, newSeenVersionNo, content, reason, payload, store, rules, condition);
  }

  public ActionStep<S> withContent(DigestInput newContent) {
    return new ActionStep<>(
        type, target, seenVersionNo, newContent, reason, payload, store, rules, condition);
  }

  public ActionStep<S> withReason(String newReason) {
    return new ActionStep<>(
        type, target, seenVersionNo, content, newReason, payload, store, rules, condition);
  }

  public ActionStep<S> withPayload(Map<String, Object> newPayload) {
    return new ActionStep<>(
        type, target, seenVersionNo, content, reason, newPayload, store, rules, condition);
  }
}
