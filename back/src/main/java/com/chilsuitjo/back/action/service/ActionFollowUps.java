package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.rule.ActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 단계 도중에 생기는 <b>딸린 행동</b>을 남기는 창구(09 3-1, decisions 2026-11-17). 그 단계의 {@code StateStore.save}나
 * {@code save} 안에서 동기로 도는 {@code @EventListener}에서만 부른다. pipeline이 그 단계를 기록한 뒤 이어서 기록한다.
 *
 * <p>창구는 단계가 끝날 때 닫힌다. 그래서 {@code @TransactionalEventListener}(커밋 직전 {@code BEFORE_COMMIT} 포함)나 비동기
 * 리스너에서 부르면 {@link IllegalStateException}이 나고 요청 전체가 되돌려진다.
 *
 * <pre>{@code
 * // 정산이 바뀌어 기다리던 끝내기 요청을 닫을 때(정산 쪽 save, 또는 save 안에서 낸 PaymentAmountConfirmed를 받는 @EventListener)
 * followUps.add(Actor.server(), ActionType.CLOSE_FINISH_REQUEST, ActionTarget.settlement(id), Map.of());
 * }</pre>
 *
 * <p>같은 호실에만 남긴다. 상태를 옮기는 딸린 행동은 서버 행동만 허용한다. 그 도메인의 {@code Transitions.apply}에 같은 전이가 있고 {@code
 * CHANGES}에 포함되어, {@code Replay.fold}로 행과 같은 상태가 나와야 한다(#363 리뷰 합의). pipeline 밖에서 부르면 {@link
 * IllegalStateException}.
 */
@Component
public class ActionFollowUps {

  /** 딸린 행동 하나. */
  public record FollowUp(
      Actor actor, ActionType type, ActionTarget target, Map<String, Object> payload) {

    public FollowUp {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(type, "type");
      payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
  }

  private final ThreadLocal<List<FollowUp>> current = new ThreadLocal<>();

  public void add(Actor actor, ActionType type, ActionTarget target, Map<String, Object> payload) {
    List<FollowUp> list = current.get();
    if (list == null) {
      throw new IllegalStateException(
          "딸린 행동은 pipeline 단계 안(save나 save 안에서 동기로 도는 @EventListener)에서만 남길 수 있어요. @TransactionalEventListener·비동기 리스너에서는 안 돼요");
    }
    list.add(new FollowUp(actor, type, target, payload));
  }

  void begin() {
    if (current.get() != null) {
      throw new IllegalStateException("pipeline 안에서 pipeline을 다시 부르지 않아요");
    }
    current.set(new ArrayList<>());
  }

  List<FollowUp> drain() {
    List<FollowUp> list = current.get();
    if (list == null) {
      return List.of();
    }
    List<FollowUp> copy = List.copyOf(list);
    list.clear();
    return copy;
  }

  void end() {
    current.remove();
  }
}
