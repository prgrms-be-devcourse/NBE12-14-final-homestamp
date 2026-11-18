package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 09 3-1 순서·계약을 가짜 부품으로 확인한다. Spring·DB 없이 돈다. */
class ActionPipelineTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
  private final FakeActionWriter writer = new FakeActionWriter(clock);
  private final FakeRoomGateReader gates = new FakeRoomGateReader();
  private final ActionFollowUps followUps = new ActionFollowUps();
  private final NoOpTransactionManager transactionManager = new NoOpTransactionManager();
  private final ActionPipeline pipeline =
      new ActionPipeline(gates, writer, followUps, transactionManager, clock);

  private final SampleStore store = new SampleStore();
  private final SampleTransitions rules = new SampleTransitions();

  private final UUID room = UUID.randomUUID();
  private final UUID tenantId = UUID.randomUUID();
  private final UUID landlordId = UUID.randomUUID();

  ActionPipelineTest() {
    gates.member(room, tenantId, PartySide.TENANT);
    gates.member(room, landlordId, PartySide.LANDLORD);
  }

  @Test
  void 행동이_순서대로_쌓이고_재계산과_같다() {
    PipelineResult<Sample> registered = pipeline.execute(register("k1"), store, rules);
    ActionTarget target = registered.action().target();
    assertThat(target.kind()).isEqualTo(TargetKind.REPAIR_CASE);

    pipeline.execute(respond(landlordId, "k2", target, Answer.ACK), store, rules);
    PipelineResult<Sample> started =
        pipeline.execute(command(tenantId, ActionType.START_REPAIR, "k3", target), store, rules);

    assertThat(started.state()).isEqualTo(new Sample("IN_PROGRESS", "ACK"));
    assertThat(started.action().roomSeq()).isEqualTo(3);
    assertThat(Replay.fold(rules, writer.findByTarget(target))).isEqualTo(store.get(target));
  }

  @Test
  void 회원의_측은_잠근_뒤_읽은_RoomGate에서_채운다() {
    pipeline.execute(register("k1"), store, rules);

    assertThat(writer.findByRoom(room).getFirst().actorSide()).isEqualTo(PartySide.TENANT);
  }

  @Test
  void 같은_키로_다시_오면_새로_남기지_않고_처음_결과와_지금_상태를_돌려준다() {
    PipelineResult<Sample> first = pipeline.execute(register("k1"), store, rules);
    PipelineResult<Sample> again = pipeline.execute(register("k1"), store, rules);

    assertThat(again.action().replayed()).isTrue();
    assertThat(again.action().actionId()).isEqualTo(first.action().actionId());
    assertThat(again.action().target()).isEqualTo(first.action().target());
    assertThat(again.state()).isEqualTo(first.state());
    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 같은_키에_다른_행동이면_IDEMPOTENCY_KEY_REUSED() {
    PipelineResult<Sample> registered = pipeline.execute(register("k1"), store, rules);

    assertThatThrownBy(() ->
            pipeline.execute(
                command(tenantId, ActionType.START_REPAIR, "k1", registered.action().target()),
                store,
                rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 규칙_위반이면_저장도_기록도_하지_않는다() {
    ActionTarget target = pipeline.execute(register("k1"), store, rules).action().target();

    assertThatThrownBy(() ->
            pipeline.execute(
                command(tenantId, ActionType.REQUEST_CLOSE, "k2", target), store, rules))
        .isInstanceOf(BusinessException.class);
    assertThat(writer.findByRoom(room)).hasSize(1);
    assertThat(store.get(target).status()).isEqualTo("NOT_STARTED");
  }

  @Test
  void 여러_행동은_키를_이어_붙이고_앞_단계가_만든_대상을_다음_단계가_쓴다() {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", 3))
                .step(ActionType.REGISTER, done -> null, store, rules)
                .step(ActionType.START_REPAIR, done -> done.get(1).target(), store, rules));

    assertThat(result.actions()).extracting(ActionResult::roomSeq).containsExactly(1L, 2L, 3L);
    assertThat(result.actions().get(2).target()).isEqualTo(result.actions().get(1).target());
    assertThat(writer.findRequest(Actor.member(tenantId).key(), "k1"))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.SHARE_CONSENT, ActionType.REGISTER, ActionType.START_REPAIR);
    assertThat(store.get(result.actions().get(1).target()).status()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void 두번째_단계가_실패하면_첫_행동도_남지_않는다() {
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(ActionType.REGISTER, done -> null, store, rules)
                    .step(ActionType.REQUEST_CLOSE, done -> done.get(0).target(), store, rules)))
        .isInstanceOf(BusinessException.class);

    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void 당사자가_아니면_NOT_FOUND() {
    assertThatThrownBy(() ->
            pipeline.execute(
                command(UUID.randomUUID(), ActionType.REGISTER, "k1", null), store, rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.NOT_FOUND.name());
  }

  @Test
  void 정산_완료된_호실은_SETTLED이고_사진_지우기만_된다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false));

    assertThatThrownBy(() -> pipeline.execute(register("k1"), store, rules))
        .hasMessage(ErrorCode.SETTLED.name());
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k2")
            .record(ActionType.ERASE_PHOTO, Map.of()));
    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.ERASE_PHOTO);
  }

  @Test
  void 합류_전_기록은_JOINED_RECORD() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, true, false));

    assertThatThrownBy(() -> pipeline.execute(register("k1"), store, rules))
        .hasMessage(ErrorCode.JOINED_RECORD.name());
  }

  @Test
  void 공유를_그만둔_사람은_응답할_수_없고_내_기록은_남긴다() {
    ActionTarget target = pipeline.execute(register("k1"), store, rules).action().target();
    gates.put(landlordId, new RoomGate(room, PartySide.LANDLORD, false, false, true));

    assertThatThrownBy(
            () -> pipeline.execute(respond(landlordId, "k2", target, Answer.ACK), store, rules))
        .hasMessage(ErrorCode.SHARING_STOPPED.name());
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(landlordId), "k3")
            .record(ActionType.NOTE_PROGRESS, Map.of()));
    assertThat(writer.findByRoom(room)).hasSize(2);
  }

  @Test
  void 서버_행동은_호실_단위_거절을_건너뛴다() {
    gates.putRoom(new RoomGate(room, null, true, false, false));

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.server(), "autoclose:" + room)
                .record(ActionType.AUTOCLOSE, Map.of()));

    assertThat(result.first().target()).isEqualTo(ActionTarget.roomRecord(room));
  }

  @Test
  void when이_거짓이면_기록_없이_건너뛴다() {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules)
                .step(
                    ActionStep.of(
                            ActionType.START_REPAIR, done -> done.get(0).target(), store, rules)
                        .when(now -> !now.status().equals("NOT_STARTED"))));

    assertThat(result.actions()).hasSize(1);
    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.REGISTER);
  }

  @Test
  void 딸린_행동은_같은_요청에_이어서_서버_주체로_남는다() {
    ActionTarget settlement = ActionTarget.settlement(UUID.randomUUID());
    store.afterSave =
        () -> followUps.add(Actor.server(), ActionType.CLOSE_FINISH_REQUEST, settlement, Map.of());

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules));

    assertThat(result.actions()).hasSize(2);
    List<ActionEntry> entries = writer.findByRoom(room);
    assertThat(entries.get(1).type()).isEqualTo(ActionType.CLOSE_FINISH_REQUEST);
    assertThat(entries.get(1).actorKey()).isEqualTo("server");
    assertThat(entries.get(1).target()).isEqualTo(settlement);
    assertThat(entries.get(1).roomSeq()).isEqualTo(2);
  }

  @Test
  void 딸린_행동은_pipeline_밖에서_남길_수_없다() {
    assertThatThrownBy(
            () -> followUps.add(Actor.server(), ActionType.CLOSE_FINISH_REQUEST, null, Map.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 호실을_만들면_첫_행동이_1번이고_다시_와도_같은_호실을_돌려준다() {
    RoomStore rooms = new RoomStore();
    ActionBatch create =
        ActionBatch.newRoom(Actor.member(tenantId), "create")
            .step(ActionType.CREATE_ROOM, done -> null, rooms, rules);

    BatchResult first = pipeline.executeAll(create);
    UUID created = first.first().target().id();
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.newRoom(Actor.member(tenantId), "create")
                .step(ActionType.CREATE_ROOM, done -> null, rooms, rules));

    assertThat(first.first().target().kind()).isEqualTo(TargetKind.ROOM_RECORD);
    assertThat(first.first().roomSeq()).isEqualTo(1);
    assertThat(writer.findByRoom(created).getFirst().target()).isNull();
    assertThat(again.replayed()).isTrue();
    assertThat(again.first().target().id()).isEqualTo(created);
    assertThat(rooms.created).hasSize(1);
  }

  @Test
  void 바깥에_트랜잭션이_있으면_거절한다() {
    TransactionTemplate outer = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () -> outer.executeWithoutResult(s -> pipeline.execute(register("k1"), store, rules)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 같은_키가_동시에_와서_제약에_걸리면_새_트랜잭션에서_처음_결과를_돌려준다() {
    RacingWriter racing = new RacingWriter(writer);
    ActionPipeline racingPipeline =
        new ActionPipeline(gates, racing, followUps, transactionManager, clock);
    PipelineResult<Sample> winner = pipeline.execute(register("k1"), store, rules);

    PipelineResult<Sample> loser = racingPipeline.execute(register("k1"), store, rules);

    assertThat(loser.action().replayed()).isTrue();
    assertThat(loser.action().actionId()).isEqualTo(winner.action().actionId());
    assertThat(transactionManager.rollbacks).isEqualTo(1);
  }

  @Test
  void payloadAs는_도메인의_입력_record로_바꿔_읽는다() {
    record RespondPayload(Answer answer, UUID photoId, long count) {}
    UUID photo = UUID.randomUUID();
    ActionEntry entry =
        new ActionEntry(
            null,
            room,
            0,
            ActionType.RESPOND_DEFECT,
            PartySide.TENANT,
            "k",
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("answer", "NEED_MORE", "photoId", photo.toString(), "count", 3),
            null);

    assertThat(entry.payloadAs(RespondPayload.class))
        .isEqualTo(new RespondPayload(Answer.NEED_MORE, photo, 3L));
  }

  @Test
  void 딸린_행동_뒤의_단계도_키가_이어지고_다시_오면_모두_replayed() {
    ActionTarget settlement = ActionTarget.settlement(UUID.randomUUID());
    store.afterSave =
        () -> {
          followUps.add(Actor.server(), ActionType.CLOSE_FINISH_REQUEST, settlement, Map.of());
          store.afterSave = () -> {};
        };
    ActionBatch batch =
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .step(ActionType.REGISTER, done -> null, store, rules)
            .step(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules);

    BatchResult first = pipeline.executeAll(batch);
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules)
                .step(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules));

    assertThat(first.actions()).extracting(ActionResult::roomSeq).containsExactly(1L, 2L, 3L);
    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(
            ActionType.REGISTER, ActionType.CLOSE_FINISH_REQUEST, ActionType.START_REPAIR);
    assertThat(again.replayed()).isTrue();
    assertThat(again.actions()).hasSize(3).allMatch(ActionResult::replayed);
    assertThat(writer.findByRoom(room)).hasSize(3);
  }

  @Test
  void 가운데_단계를_건너뛰어도_뒤_단계_키가_이어지고_다시_오면_같은_결과() {
    ActionBatch batch =
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .step(ActionType.REGISTER, done -> null, store, rules)
            .step(
                ActionStep.of(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules)
                    .when(now -> false))
            .record(ActionType.SHARE_CONSENT, Map.of());

    BatchResult first = pipeline.executeAll(batch);
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules)
                .step(
                    ActionStep.of(
                            ActionType.START_REPAIR, done -> done.get(0).target(), store, rules)
                        .when(now -> false))
                .record(ActionType.SHARE_CONSENT, Map.of()));

    assertThat(writer.findRequest(Actor.member(tenantId).key(), "k1"))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.REGISTER, ActionType.SHARE_CONSENT);
    assertThat(again.actions())
        .extracting(ActionResult::actionId)
        .containsExactlyElementsOf(first.actions().stream().map(ActionResult::actionId).toList());
  }

  @Test
  void 모든_단계를_건너뛰면_아무것도_남기지_않고_끝난다() {
    ActionTarget target = pipeline.execute(register("k1"), store, rules).action().target();

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k2")
                .step(
                    ActionStep.of(ActionType.START_REPAIR, done -> target, store, rules)
                        .when(now -> false)));

    assertThat(result.actions()).isEmpty();
    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 호실_단위_거절은_when보다_먼저_본다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(
                        ActionStep.of(ActionType.REGISTER, done -> null, store, rules)
                            .when(now -> false))))
        .hasMessage(ErrorCode.SETTLED.name());
  }

  @Test
  void 운영_행동도_호실_단위_거절을_건너뛴다() {
    gates.putRoom(new RoomGate(room, null, true, true, false));

    pipeline.executeAll(
        ActionBatch.of(room, Actor.ops("홍길동"), "ops-1").record(ActionType.HIDE_PHOTO, Map.of()));

    assertThat(writer.findByRoom(room).getFirst().actorKey()).isEqualTo("ops:홍길동");
  }

  @Test
  void 공유를_그만둔_사람은_초대할_수_없다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, false, true));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .record(ActionType.INVITE, Map.of())))
        .hasMessage(ErrorCode.SHARING_STOPPED.name());
  }

  @Test
  void 합류_전_기록에서도_사진_지우기는_된다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, true, false));

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.ERASE_PHOTO, Map.of()));

    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 호실_기록_자체가_대상이면_행동_기록에는_대상_칸이_없다() {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(
                    new ActionStep<Void>(
                        ActionType.MOVE_OUT,
                        done -> ActionTarget.roomRecord(room),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));

    assertThat(writer.findByRoom(room).getFirst().target()).isNull();
    assertThat(result.first().target()).isEqualTo(ActionTarget.roomRecord(room));
  }

  @Test
  void save가_다른_호실을_돌려주면_기록하지_않는다() {
    StateStore<Sample> wrong =
        new StateStore<>() {
          @Override
          public Sample load(ActionCommand command) {
            return rules.initial();
          }

          @Override
          public SavedState save(ActionCommand command, Sample next) {
            return SavedState.created(ActionTarget.roomRecord(UUID.randomUUID()));
          }
        };

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(ActionType.REGISTER, done -> null, wrong, rules)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void 호실을_만들_때_측은_부르는_쪽이_넣는다() {
    pipeline.executeAll(
        ActionBatch.newRoom(Actor.member(tenantId).withSide(PartySide.TENANT), "create")
            .step(ActionType.CREATE_ROOM, done -> null, new RoomStore(), rules));

    UUID created =
        writer.findRequest(Actor.member(tenantId).key(), "create").getFirst().roomRecordId();
    assertThat(writer.findByRoom(created).getFirst().actorSide()).isEqualTo(PartySide.TENANT);
  }

  @Test
  void 잘못_부른_요청은_벨트에_들어가지_않는다() {
    assertThatThrownBy(() -> ActionBatch.of(room, Actor.member(tenantId), "a#b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pipeline.executeAll(ActionBatch.of(room, Actor.member(tenantId), "k")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            pipeline.execute(
                new ActionCommand(
                    null,
                    Actor.member(tenantId),
                    ActionType.REGISTER,
                    "k",
                    null,
                    null,
                    null,
                    null,
                    Map.of()),
                store,
                rules))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- 같은 키로 다시 온 요청이 다른 요청과 섞이지 않는다(06 4-0, #64 리뷰) ----

  @Test
  void 다른_호실에서_같은_키로_오면_IDEMPOTENCY_KEY_REUSED() {
    UUID otherRoom = UUID.randomUUID();
    gates.member(otherRoom, tenantId, PartySide.TENANT);
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.SHARE_CONSENT, Map.of()));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(otherRoom, Actor.member(tenantId), "k1")
                    .record(ActionType.SHARE_CONSENT, Map.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 행동_순서가_바뀐_묶음이면_IDEMPOTENCY_KEY_REUSED() {
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.SHARE_CONSENT, Map.of())
            .step(ActionType.REGISTER, done -> null, store, rules));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(ActionType.REGISTER, done -> null, store, rules)
                    .record(ActionType.SHARE_CONSENT, Map.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 행동이_빠진_묶음이면_IDEMPOTENCY_KEY_REUSED() {
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.SHARE_CONSENT, Map.of())
            .step(ActionType.REGISTER, done -> null, store, rules));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .record(ActionType.SHARE_CONSENT, Map.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 첫_단계를_건너뛴_묶음도_다음_단계의_대상이_다르면_IDEMPOTENCY_KEY_REUSED() {
    ActionTarget first = pipeline.execute(register("r1"), store, rules).action().target();
    ActionTarget second = pipeline.execute(register("r2"), store, rules).action().target();
    pipeline.executeAll(skippedThenStart(first));

    assertThat(pipeline.executeAll(skippedThenStart(first)).replayed()).isTrue();
    assertThatThrownBy(() -> pipeline.executeAll(skippedThenStart(second)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 같은_호실의_두_회원이_같은_키를_써도_다시_온_결과가_섞이지_않는다() {
    BatchResult tenantFirst = pipeline.executeAll(registerThenStart(tenantId));
    BatchResult landlordFirst = pipeline.executeAll(registerThenStart(landlordId));

    BatchResult tenantAgain = pipeline.executeAll(registerThenStart(tenantId));

    assertThat(landlordFirst.replayed()).isFalse();
    assertThat(tenantAgain.replayed()).isTrue();
    assertThat(tenantAgain.actions())
        .extracting(ActionResult::actionId)
        .containsExactlyElementsOf(ids(tenantFirst));
  }

  @Test
  void 두_회원이_같은_키를_써도_서버_딸린_행동끼리_부딪히지_않는다() {
    ActionTarget settlement = ActionTarget.settlement(UUID.randomUUID());
    store.afterSave =
        () -> followUps.add(Actor.server(), ActionType.CLOSE_FINISH_REQUEST, settlement, Map.of());

    BatchResult tenant =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules));
    BatchResult landlord =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(landlordId), "k1")
                .step(ActionType.REGISTER, done -> null, store, rules));

    assertThat(landlord.replayed()).isFalse();
    assertThat(writer.findByRoom(room)).hasSize(4);
    assertThat(writer.findRequest(Actor.member(landlordId).key(), "k1"))
        .extracting(ActionEntry::id)
        .containsExactlyElementsOf(ids(landlord));
    assertThat(writer.findRequest(Actor.member(tenantId).key(), "k1"))
        .extracting(ActionEntry::id)
        .containsExactlyElementsOf(ids(tenant));
  }

  @Test
  void 한_요청의_행동은_모두_같은_request_id를_갖고_서버_딸린_행동_키는_request_id로_시작한다() {
    store.afterSave =
        () ->
            followUps.add(
                Actor.server(),
                ActionType.CLOSE_FINISH_REQUEST,
                ActionTarget.settlement(UUID.randomUUID()),
                Map.of());

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .step(ActionType.REGISTER, done -> null, store, rules));

    List<ActionEntry> entries = writer.findRequest(Actor.member(tenantId).key(), "k1");
    UUID requestId = entries.getFirst().requestId();
    assertThat(requestId).isNotNull();
    assertThat(entries).allMatch(entry -> requestId.equals(entry.requestId()));
    assertThat(entries)
        .extracting(ActionEntry::idempotencyKey)
        .containsExactly("k1", requestId + "#2");
  }

  private ActionBatch skippedThenStart(ActionTarget target) {
    return ActionBatch.of(room, Actor.member(tenantId), "k1")
        .step(
            ActionStep.of(ActionType.START_REPAIR, done -> target, store, rules)
                .when(now -> now.status().equals("IN_PROGRESS")))
        .step(ActionType.START_REPAIR, done -> target, store, rules);
  }

  private ActionBatch registerThenStart(UUID memberId) {
    return ActionBatch.of(room, Actor.member(memberId), "k1")
        .step(ActionType.REGISTER, done -> null, store, rules)
        .step(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules);
  }

  private static List<UUID> ids(BatchResult result) {
    return result.actions().stream().map(ActionResult::actionId).toList();
  }

  private ActionCommand register(String key) {
    return command(tenantId, ActionType.REGISTER, key, null);
  }

  private ActionCommand respond(UUID memberId, String key, ActionTarget target, Answer answer) {
    return new ActionCommand(
        room,
        Actor.member(memberId),
        ActionType.RESPOND_DEFECT,
        key,
        target,
        null,
        null,
        null,
        Map.of("answer", answer));
  }

  private ActionCommand command(UUID memberId, ActionType type, String key, ActionTarget target) {
    return new ActionCommand(
        room, Actor.member(memberId), type, key, target, null, null, null, Map.of());
  }

  /** 테스트용 상태 값. 실제 수리 상태(REP-1)와 상관없다. */
  record Sample(String status, String defectAck) {}

  record AnswerPayload(Answer answer) {}

  /** 테스트용 규칙: 등록 → 하자 응답 → 시작 → 종료 요청. */
  static final class SampleTransitions implements Transitions<Sample> {
    @Override
    public Sample initial() {
      return new Sample("NONE", "PENDING");
    }

    @Override
    public Sample apply(Sample now, ActionEntry action) {
      return switch (action.type()) {
        case REGISTER, CREATE_ROOM -> new Sample("NOT_STARTED", "PENDING");
        case RESPOND_DEFECT ->
            new Sample(now.status(), action.payloadAs(AnswerPayload.class).answer().name());
        case START_REPAIR -> new Sample("IN_PROGRESS", now.defectAck());
        case REQUEST_CLOSE -> {
          if (!now.status().equals("IN_PROGRESS")) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
          }
          yield new Sample("CLOSE_WAIT", now.defectAck());
        }
        default -> throw new BusinessException(ErrorCode.INVALID_STATE);
      };
    }
  }

  /** 테스트용 빈칸: 메모리에 상태 행을 둔다. */
  static final class SampleStore implements StateStore<Sample> {
    private final Map<ActionTarget, Sample> rows = new HashMap<>();
    Runnable afterSave = () -> {};

    @Override
    public Sample load(ActionCommand command) {
      return command.target() == null
          ? new SampleTransitions().initial()
          : rows.getOrDefault(command.target(), new SampleTransitions().initial());
    }

    @Override
    public SavedState save(ActionCommand command, Sample next) {
      afterSave.run();
      if (command.target() == null) {
        ActionTarget created = ActionTarget.repairCase(UUID.randomUUID());
        rows.put(created, next);
        return SavedState.created(created);
      }
      rows.put(command.target(), next);
      return SavedState.unchanged();
    }

    Sample get(ActionTarget target) {
      return rows.get(target);
    }
  }

  /** 호실을 만드는 빈칸: 새 호실 ID를 SavedState로 돌려준다. */
  static final class RoomStore implements StateStore<Sample> {
    final List<UUID> created = new ArrayList<>();

    @Override
    public Sample load(ActionCommand command) {
      return new SampleTransitions().initial();
    }

    @Override
    public SavedState save(ActionCommand command, Sample next) {
      UUID id = UUID.randomUUID();
      created.add(id);
      return SavedState.created(ActionTarget.roomRecord(id));
    }
  }

  /** 같은 키가 동시에 온 상황: 처음 찾을 때는 없다가, 쓰려는 순간 먼저 끝난 요청 때문에 제약에 걸린다. */
  static final class RacingWriter implements ActionWriter {
    private final FakeActionWriter delegate;
    private boolean firstLookup = true;

    RacingWriter(FakeActionWriter delegate) {
      this.delegate = delegate;
    }

    @Override
    public ActionEntry write(ActionCommand command, UUID requestId) {
      throw new DataIntegrityViolationException("uq_action_idempotency");
    }

    @Override
    public List<ActionEntry> findRequest(String actorKey, String idempotencyKey) {
      if (firstLookup) {
        firstLookup = false;
        return List.of();
      }
      return delegate.findRequest(actorKey, idempotencyKey);
    }
  }

  /** DB 없이 트랜잭션 경계만 흉내 낸다(동기화 콜백은 실제처럼 돈다). */
  static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
