package com.chilsuitjo.back.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.PipelineResult;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.action.service.ActionPipelineFixture;
import com.chilsuitjo.back.action.service.StateStore;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 다른 패키지(다른 도메인)에서 {@link ActionPipelineFixture}로 pipeline을 쓰는 모양. docs/guides/action-pipeline.md의
 * 예시와 같다.
 */
class ActionPipelineFixtureTest {

  private final ActionPipelineFixture fx = new ActionPipelineFixture();
  private final MemoStore store = new MemoStore();
  private final MemoTransitions rules = new MemoTransitions();

  private final UUID room = UUID.randomUUID();
  private final UUID tenantId = UUID.randomUUID();

  @Test
  void 다른_패키지에서도_pipeline으로_새_기록을_만들고_행동이_남는다() {
    fx.gates().member(room, tenantId, PartySide.TENANT);

    PipelineResult<Memo> result = fx.pipeline().create(register("k1"), store, rules);

    assertThat(result.state().status()).isEqualTo("OPEN");
    assertThat(result.action().target()).isEqualTo(store.lastCreated);
    assertThat(fx.actions().findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.REGISTER);
  }

  @Test
  void 같은_키로_다시_오면_새로_만들지_않고_처음_결과를_돌려준다() {
    fx.gates().member(room, tenantId, PartySide.TENANT);

    PipelineResult<Memo> first = fx.pipeline().create(register("k1"), store, rules);
    PipelineResult<Memo> again = fx.pipeline().create(register("k1"), store, rules);

    assertThat(again.action().replayed()).isTrue();
    assertThat(again.action().target()).isEqualTo(first.action().target());
    assertThat(fx.actions().findByRoom(room)).hasSize(1);
  }

  @Test
  void 정산_완료된_호실이면_도메인_규칙까지_가지_않고_거절된다() {
    fx.gates().put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false, true));

    assertThatThrownBy(() -> fx.pipeline().create(register("k1"), store, rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.SETTLED.name());
    assertThat(fx.actions().findByRoom(room)).isEmpty();
    assertThat(store.rows).isEmpty();
  }

  private ActionCommand register(String key) {
    return new ActionCommand(
        room, Actor.member(tenantId), ActionType.REGISTER, key, null, null, null, null, Map.of());
  }

  /** 도메인의 상태 값(불변 record). */
  record Memo(String status) {}

  /** 도메인의 규칙: 순수 함수. Repository·Clock을 부르지 않는다. */
  static final class MemoTransitions implements Transitions<Memo> {
    @Override
    public Memo initial() {
      return new Memo("NONE");
    }

    @Override
    public Memo apply(Memo now, ActionEntry action) {
      if (action.type() != ActionType.REGISTER) {
        throw new BusinessException(ErrorCode.INVALID_STATE);
      }
      return new Memo("OPEN");
    }
  }

  /** 도메인의 저장: 실제로는 Repository로 엔티티를 읽고 쓴다. */
  static final class MemoStore implements StateStore<Memo> {
    final Map<ActionTarget, Memo> rows = new HashMap<>();
    ActionTarget lastCreated;

    @Override
    public Memo load(ActionCommand command) {
      return command.target() == null ? new Memo("NONE") : rows.get(command.target());
    }

    @Override
    public SavedState save(ActionCommand command, Memo next) {
      if (command.target() == null) {
        lastCreated = ActionTarget.repairCase(UUID.randomUUID());
        rows.put(lastCreated, next);
        return SavedState.created(lastCreated);
      }
      rows.put(command.target(), next);
      return SavedState.unchanged();
    }
  }
}
