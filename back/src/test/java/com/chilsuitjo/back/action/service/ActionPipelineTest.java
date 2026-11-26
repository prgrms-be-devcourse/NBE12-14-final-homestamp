package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.rule.Transitions;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 09 3-1 순서·계약을 가짜 부품으로 확인한다. Spring·DB 없이 돈다. */
class ActionPipelineTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
  private final FakeActionWriter writer = new FakeActionWriter();
  private final FakeRoomGateReader gates = new FakeRoomGateReader();
  private final ActionFollowUps followUps = new ActionFollowUps();
  private final ActionRequestContext requestContext = new ActionRequestContext();
  private final ActionPipelineFixture.NoOpTransactionManager transactionManager =
      new ActionPipelineFixture.NoOpTransactionManager();
  private final ActionPipeline pipeline =
      new ActionPipeline(gates, writer, followUps, requestContext, transactionManager, clock);

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
    PipelineResult<Sample> registered = pipeline.create(register("k1"), store, rules);
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
    pipeline.create(register("k1"), store, rules);

    assertThat(writer.findByRoom(room).getFirst().actorSide()).isEqualTo(PartySide.TENANT);
  }

  @Test
  void 같은_키로_다시_오면_새로_남기지_않고_처음_결과와_지금_상태를_돌려준다() {
    PipelineResult<Sample> first = pipeline.create(register("k1"), store, rules);
    PipelineResult<Sample> again = pipeline.create(register("k1"), store, rules);

    assertThat(again.action().replayed()).isTrue();
    assertThat(again.action().actionId()).isEqualTo(first.action().actionId());
    assertThat(again.action().target()).isEqualTo(first.action().target());
    assertThat(again.state()).isEqualTo(first.state());
    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 같은_키에_다른_행동이면_IDEMPOTENCY_KEY_REUSED() {
    PipelineResult<Sample> registered = pipeline.create(register("k1"), store, rules);

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
    ActionTarget target = pipeline.create(register("k1"), store, rules).action().target();

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
                .creating(ActionType.REGISTER, store, rules)
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
                    .creating(ActionType.REGISTER, store, rules)
                    .step(ActionType.REQUEST_CLOSE, done -> done.get(0).target(), store, rules)))
        .isInstanceOf(BusinessException.class);

    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void 당사자가_아니면_NOT_FOUND() {
    assertThatThrownBy(() ->
            pipeline.create(
                command(UUID.randomUUID(), ActionType.REGISTER, "k1", null), store, rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.NOT_FOUND.name());
  }

  @Test
  void 정산_완료된_호실은_SETTLED이고_사진_지우기는_된다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false, true));

    assertThatThrownBy(() -> pipeline.create(register("k1"), store, rules))
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
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, true, false, false));

    assertThatThrownBy(() -> pipeline.create(register("k1"), store, rules))
        .hasMessage(ErrorCode.JOINED_RECORD.name());
  }

  @Test
  void 공유를_그만둔_사람은_응답할_수_없고_내_기록은_남긴다() {
    ActionTarget target = pipeline.create(register("k1"), store, rules).action().target();
    gates.put(landlordId, new RoomGate(room, PartySide.LANDLORD, false, false, true, false));

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
    gates.putRoom(new RoomGate(room, null, true, false, false, true));

    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.server(), "autoclose:" + room)
                .record(ActionType.AUTOCLOSE, Map.of()));

    assertThat(result.first().target()).isEqualTo(ActionTarget.roomRecord(room));
  }

  @Test
  void 초대_자리로_들어온_요청은_허용된_행동만_되고_측은_그_자리의_측이다() {
    UUID inviteeId = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    gates.invitee(room, partyId, PartySide.LANDLORD);

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(inviteeId), "k1")
            .asInvitee(partyId)
            .record(ActionType.CONNECT, Map.of())
            .record(ActionType.SHARE_CONSENT, Map.of("consentVersion", 3)));

    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type, ActionEntry::actorSide, ActionEntry::actorKey)
        .containsExactly(
            tuple(ActionType.CONNECT, PartySide.LANDLORD, inviteeId.toString()),
            tuple(ActionType.SHARE_CONSENT, PartySide.LANDLORD, inviteeId.toString()));
    // 초대 자리로 열지 않으면 당사자가 아니라 NOT_FOUND(ROOM-2a), 초대 자리여도 허용되지 않은 행동은 NOT_FOUND
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(inviteeId), "k2")
                    .record(ActionType.REJECT_INVITE, Map.of())))
        .hasMessage(ErrorCode.NOT_FOUND.name());
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(inviteeId), "k3")
                    .asInvitee(partyId)
                    .creating(ActionType.REGISTER, store, rules)))
        .hasMessage(ErrorCode.NOT_FOUND.name());
    assertThat(writer.findByRoom(room)).hasSize(2);
  }

  @Test
  void 초대_자리의_허용되지_않은_행동은_정산_완료_호실에서도_SETTLED가_아니라_NOT_FOUND다() {
    UUID inviteeId = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    gates.invitee(room, partyId, PartySide.LANDLORD);
    gates.putRoom(new RoomGate(room, null, true, false, false, true));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(inviteeId), "k1")
                    .asInvitee(partyId)
                    .record(ActionType.NOTE_PROGRESS, Map.of())))
        .hasMessage(ErrorCode.NOT_FOUND.name());
    // 허용된 행동은 호실의 다른 막힘을 그대로 받는다
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(inviteeId), "k2")
                    .asInvitee(partyId)
                    .record(ActionType.CONNECT, Map.of())))
        .hasMessage(ErrorCode.SETTLED.name());
  }

  @Test
  void 같은_키로_다시_온_초대_자리_요청은_초대_자리로_잠가_처음_결과를_돌려준다() {
    UUID inviteeId = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    gates.invitee(room, partyId, PartySide.LANDLORD);
    ActionBatch connect =
        ActionBatch.of(room, Actor.member(inviteeId), "k1")
            .asInvitee(partyId)
            .record(ActionType.CONNECT, Map.of());

    BatchResult first = pipeline.executeAll(connect);
    BatchResult again = pipeline.executeAll(connect);

    assertThat(again.replayed()).isTrue();
    assertThat(again.first().actionId()).isEqualTo(first.first().actionId());
    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 이_호실의_자리가_아니면_초대_자리로_잠글_때_NOT_FOUND다() {
    UUID otherRoom = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    gates.invitee(otherRoom, partyId, PartySide.LANDLORD);

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(UUID.randomUUID()), "k1")
                    .asInvitee(partyId)
                    .record(ActionType.CONNECT, Map.of())))
        .hasMessage(ErrorCode.NOT_FOUND.name());
  }

  @Test
  void 초대_자리는_회원_주체의_기존_호실_요청에만_한_번_붙인다() {
    UUID partyId = UUID.randomUUID();

    assertThatThrownBy(() -> ActionBatch.newRoom(Actor.member(tenantId), "k1").asInvitee(partyId))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> ActionBatch.of(room, Actor.server(), "k2").asInvitee(partyId))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            ActionBatch.of(room, Actor.member(tenantId), "k3")
                .asInvitee(partyId)
                .asInvitee(partyId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 초대_자리로_잠갔는데_invitee가_아닌_값이_오면_실행하지_않는다() {
    RoomGateReader forgetful =
        new RoomGateReader() {
          @Override
          public RoomGate lockAndRead(UUID roomRecordId, Actor actor) {
            return gates.lockAndRead(roomRecordId, actor);
          }

          @Override
          public RoomGate lockAndReadAsInvitee(UUID roomRecordId, Actor actor, UUID partyId) {
            return RoomGate.open(roomRecordId, PartySide.LANDLORD); // invitee 표시를 빠뜨림
          }

          @Override
          public RoomGate read(UUID roomRecordId, Actor actor) {
            return gates.read(roomRecordId, actor);
          }
        };
    ActionPipeline broken =
        new ActionPipeline(forgetful, writer, followUps, requestContext, transactionManager, clock);

    assertThatThrownBy(() ->
            broken.executeAll(
                ActionBatch.of(room, Actor.member(UUID.randomUUID()), "k1")
                    .asInvitee(UUID.randomUUID())
                    .creating(ActionType.REGISTER, store, rules)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void when이_거짓이면_기록_없이_건너뛴다() {
    BatchResult result =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .creating(ActionType.REGISTER, store, rules)
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
                .creating(ActionType.REGISTER, store, rules));

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
  void 커밋_직전_리스너에서는_딸린_행동을_남길_수_없고_요청이_모두_되돌려진다() {
    // @TransactionalEventListener(BEFORE_COMMIT)와 같은 때: 단계가 끝나 창구가 닫힌 뒤 커밋 직전에 불린다
    ActionTarget settlement = ActionTarget.settlement(UUID.randomUUID());
    store.afterSave =
        () ->
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void beforeCommit(boolean readOnly) {
                    followUps.add(
                        Actor.server(), ActionType.CLOSE_FINISH_REQUEST, settlement, Map.of());
                  }
                });

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .creating(ActionType.REGISTER, store, rules)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@EventListener");
    assertThat(writer.findByRoom(room)).isEmpty();
    assertThat(transactionManager.rollbacks).isEqualTo(1);
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
            () -> outer.executeWithoutResult(s -> pipeline.create(register("k1"), store, rules)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 같은_키가_동시에_와서_제약에_걸리면_새_트랜잭션에서_처음_결과를_돌려준다() {
    RacingWriter racing = new RacingWriter(writer);
    ActionPipeline racingPipeline =
        new ActionPipeline(gates, racing, followUps, requestContext, transactionManager, clock);
    PipelineResult<Sample> winner = pipeline.create(register("k1"), store, rules);

    PipelineResult<Sample> loser = racingPipeline.create(register("k1"), store, rules);

    assertThat(loser.action().replayed()).isTrue();
    assertThat(loser.action().actionId()).isEqualTo(winner.action().actionId());
    assertThat(transactionManager.rollbacks).isEqualTo(1);
  }

  @Test
  void 충돌_뒤_새_트랜잭션에서도_호실을_먼저_잠그고_처음_결과를_찾는다() {
    List<String> events = new ArrayList<>();
    RoomGateReader lockingGates =
        new RoomGateReader() {
          @Override
          public RoomGate lockAndRead(UUID roomRecordId, Actor actor) {
            events.add("lock");
            return gates.lockAndRead(roomRecordId, actor);
          }

          @Override
          public RoomGate lockAndReadAsInvitee(UUID roomRecordId, Actor actor, UUID partyId) {
            events.add("lock");
            return gates.lockAndReadAsInvitee(roomRecordId, actor, partyId);
          }

          @Override
          public RoomGate read(UUID roomRecordId, Actor actor) {
            return gates.read(roomRecordId, actor);
          }
        };
    RacingWriter racing = new RacingWriter(writer);
    ActionWriter loggingWriter =
        new ActionWriter() {
          @Override
          public ActionEntry write(ActionCommand command, UUID requestId, Instant at) {
            return racing.write(command, requestId, at);
          }

          @Override
          public List<ActionEntry> findRequest(String actorKey, String idempotencyKey) {
            events.add("find");
            return racing.findRequest(actorKey, idempotencyKey);
          }
        };
    ActionPipeline racingPipeline =
        new ActionPipeline(
            lockingGates, loggingWriter, followUps, requestContext, transactionManager, clock);
    pipeline.create(register("k1"), store, rules);

    PipelineResult<Sample> loser = racingPipeline.create(register("k1"), store, rules);

    assertThat(loser.action().replayed()).isTrue();
    // 처음 실행(잠금 → 찾기 → 저장 충돌)과 새 트랜잭션(잠금 → 찾기)
    assertThat(events).containsExactly("lock", "find", "lock", "find");
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
            .creating(ActionType.REGISTER, store, rules)
            .step(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules);

    BatchResult first = pipeline.executeAll(batch);
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .creating(ActionType.REGISTER, store, rules)
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
  void 상태_없는_행동에도_대상을_남기고_다시_온_요청은_대상으로_맞춘다() {
    UUID linkId = UUID.randomUUID();
    Actor viewer = Actor.link(linkId, PartySide.LANDLORD);
    ActionTarget link = ActionTarget.of(TargetKind.LINK, linkId);

    BatchResult first =
        pipeline.executeAll(
            ActionBatch.of(room, viewer, "view")
                .record(ActionType.LINK_VIEW, done -> link, Map.of()));
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.of(room, viewer, "view")
                .record(ActionType.LINK_VIEW, done -> link, Map.of()));

    assertThat(writer.findByTarget(link))
        .singleElement()
        .extracting(ActionEntry::type)
        .isEqualTo(ActionType.LINK_VIEW);
    assertThat(first.first().target()).isEqualTo(link);
    assertThat(again.replayed()).isTrue();
    // 같은 키로 다른 대상이면 다른 요청이다.
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, viewer, "view")
                    .record(
                        ActionType.LINK_VIEW,
                        done -> ActionTarget.of(TargetKind.LINK, UUID.randomUUID()),
                        Map.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void 현재_접근_검증은_새_요청과_멱등_replay_모두_멱등_조회보다_먼저_실행된다() {
    List<String> order = new ArrayList<>();
    ActionTarget target = ActionTarget.of(TargetKind.LINK, UUID.randomUUID());
    ActionBatch first =
        ActionBatch.of(room, Actor.link(target.id(), PartySide.TENANT), "view")
            .accessCheck(() -> order.add("guard"))
            .record(ActionType.LINK_VIEW, done -> target, Map.of());

    pipeline.executeAll(first);
    assertThat(order).containsExactly("guard");

    pipeline.executeAll(
        ActionBatch.of(room, Actor.link(target.id(), PartySide.TENANT), "view")
            .accessCheck(() -> order.add("guard"))
            .record(ActionType.LINK_VIEW, done -> target, Map.of()));

    assertThat(order).containsExactly("guard", "guard");
    assertThat(writer.findByTarget(target)).hasSize(1);
  }

  @Test
  void 현재_접근_검증이_거절하면_저장된_성공도_replay하지_않는다() {
    ActionTarget target = ActionTarget.of(TargetKind.LINK, UUID.randomUUID());
    Actor actor = Actor.link(target.id(), PartySide.TENANT);
    pipeline.executeAll(
        ActionBatch.of(room, actor, "view").record(ActionType.LINK_VIEW, done -> target, Map.of()));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, actor, "view")
                    .accessCheck(() -> {
                      throw new BusinessException(ErrorCode.LINK_INVALID);
                    })
                    .record(ActionType.LINK_VIEW, done -> target, Map.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
    assertThat(writer.findByTarget(target)).hasSize(1);
  }

  @Test
  void 접근_검증이_붙은_요청은_호실_단위_거절이_접근_검증의_업무_오류보다_먼저다() {
    // 06 4-10 순서: 정산 완료면 접근 검증이 무엇을 던지든 SETTLED이고, 접근 검증은 부르지 않는다
    gates.putRoom(new RoomGate(room, null, true, false, false, true));
    List<String> order = new ArrayList<>();
    ActionTarget target = ActionTarget.of(TargetKind.LINK, UUID.randomUUID());

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.link(target.id(), PartySide.TENANT), "view")
                    .accessCheck(() -> {
                      order.add("guard");
                      throw new BusinessException(ErrorCode.NOT_FOUND);
                    })
                    .record(ActionType.LINK_VIEW, done -> target, Map.of())))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTLED));
    assertThat(order).isEmpty();
  }

  @Test
  void 접근_검증이_붙은_요청은_정산_완료_뒤_같은_키로_와도_저장된_성공을_재생하지_않는다() {
    ActionTarget target = ActionTarget.of(TargetKind.LINK, UUID.randomUUID());
    Actor actor = Actor.link(target.id(), PartySide.TENANT);
    pipeline.executeAll(
        ActionBatch.of(room, actor, "view")
            .accessCheck(() -> {})
            .record(ActionType.LINK_VIEW, done -> target, Map.of()));
    gates.putRoom(new RoomGate(room, null, true, false, false, true));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, actor, "view")
                    .accessCheck(() -> {})
                    .record(ActionType.LINK_VIEW, done -> target, Map.of())))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTLED));
    assertThat(writer.findByTarget(target)).hasSize(1);
  }

  @Test
  void 접근_검증이_없는_요청은_지금처럼_멱등_재생이_호실_단위_거절보다_먼저다() {
    // 같은 키로 다시 온 요청은 처음 결과를 돌려준다(06 4-0). 접근 검증을 붙인 요청만 재생 전에 호실을 본다
    PipelineResult<Sample> first = pipeline.create(register("k1"), store, rules);
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false, true));

    PipelineResult<Sample> again = pipeline.create(register("k1"), store, rules);

    assertThat(again.action().replayed()).isTrue();
    assertThat(again.action().actionId()).isEqualTo(first.action().actionId());
  }

  @Test
  void 접근_검증을_두_번_붙이면_앞의_검증이_사라지지_않게_개발_오류다() {
    ActionBatch batch =
        ActionBatch.of(room, Actor.link(UUID.randomUUID(), PartySide.TENANT), "view")
            .accessCheck(() -> {});

    assertThatThrownBy(() -> batch.accessCheck(() -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("하나만");
  }

  @Test
  void 가운데_단계를_건너뛰어도_뒤_단계_키가_이어지고_다시_오면_같은_결과() {
    ActionBatch batch =
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .creating(ActionType.REGISTER, store, rules)
            .step(
                ActionStep.of(ActionType.START_REPAIR, done -> done.get(0).target(), store, rules)
                    .when(now -> false))
            .record(ActionType.SHARE_CONSENT, Map.of());

    BatchResult first = pipeline.executeAll(batch);
    BatchResult again =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .creating(ActionType.REGISTER, store, rules)
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
    ActionTarget target = pipeline.create(register("k1"), store, rules).action().target();

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
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false, true));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(
                        ActionStep.creating(ActionType.REGISTER, store, rules).when(now -> false))))
        .hasMessage(ErrorCode.SETTLED.name());
  }

  @Test
  void 거주가_끝난_호실에는_새_수리_건을_만들_수_없다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, false, false, true));

    assertThatThrownBy(() -> pipeline.create(register("k1"), store, rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.ROOM_ENDED.name());
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k2")
            .record(ActionType.NOTE_PROGRESS, Map.of()));
    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.NOTE_PROGRESS);
  }

  @Test
  void 정산_완료이면서_거주_종료면_SETTLED가_먼저다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, false, true));

    assertThatThrownBy(() -> pipeline.create(register("k1"), store, rules))
        .hasMessage(ErrorCode.SETTLED.name());
  }

  @Test
  void 운영_행동도_호실_단위_거절을_건너뛴다() {
    gates.putRoom(new RoomGate(room, null, true, true, false, true));

    pipeline.executeAll(
        ActionBatch.of(room, Actor.ops("홍길동"), "ops-1").record(ActionType.HIDE_PHOTO, Map.of()));

    assertThat(writer.findByRoom(room).getFirst().actorKey()).isEqualTo("ops:홍길동");
  }

  @Test
  void apply가_받은_시각과_저장된_시각은_요청마다_하나이고_마이크로초로_자른다() {
    FakeActionWriter saved = new FakeActionWriter();
    ActionPipeline advancing =
        new ActionPipeline(
            gates, saved, followUps, requestContext, transactionManager, advancingClock());
    List<Instant> seen = new ArrayList<>();
    Transitions<Sample> capturing =
        new Transitions<>() {
          @Override
          public Sample initial() {
            return rules.initial();
          }

          @Override
          public Sample apply(Sample now, ActionEntry action) {
            seen.add(action.createdAt());
            return rules.apply(now, action);
          }
        };

    advancing.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .creating(ActionType.REGISTER, store, capturing)
            .record(ActionType.SHARE_CONSENT, Map.of()));

    Instant at = seen.getFirst();
    assertThat(at).isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
    assertThat(saved.findByRoom(room)).extracting(ActionEntry::createdAt).containsOnly(at);
  }

  @Test
  void 잘못된_멱등_키는_400_VALIDATION_FAILED() {
    for (String key :
        new String[] {null, "", "  ", "a#b", "k".repeat(ActionBatch.MAX_KEY_LENGTH + 1)}) {
      assertThatThrownBy(() -> ActionBatch.of(room, Actor.member(tenantId), key))
          .as(String.valueOf(key))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(ErrorCode.VALIDATION_FAILED.status().value()).isEqualTo(400);
    // 화면의 키(UUID)와 한도 길이는 받는다.
    ActionBatch.of(room, Actor.member(tenantId), UUID.randomUUID().toString());
    ActionBatch.of(room, Actor.member(tenantId), "k".repeat(ActionBatch.MAX_KEY_LENGTH));
  }

  @Test
  void save에서_읽은_시각과_requestId는_행동_기록과_같다() {
    List<Instant> seenAt = new ArrayList<>();
    List<UUID> seenRequest = new ArrayList<>();
    store.afterSave =
        () -> {
          seenAt.add(requestContext.at());
          seenRequest.add(requestContext.requestId());
        };
    ActionPipeline advancing =
        new ActionPipeline(
            gates, writer, followUps, requestContext, transactionManager, advancingClock());

    advancing.create(register("k1"), store, rules);

    ActionEntry recorded = writer.findByRoom(room).getFirst();
    assertThat(seenAt).containsExactly(recorded.createdAt());
    assertThat(seenRequest).containsExactly(recorded.requestId());
  }

  @Test
  void 요청_시각은_pipeline_밖에서_읽을_수_없고_실패해도_닫힌다() {
    assertThatThrownBy(requestContext::at).isInstanceOf(IllegalStateException.class);

    pipeline.create(register("ok"), store, rules);
    assertThatThrownBy(requestContext::requestId).isInstanceOf(IllegalStateException.class);

    store.afterSave =
        () -> {
          throw new BusinessException(ErrorCode.INVALID_STATE);
        };
    assertThatThrownBy(() -> pipeline.create(register("fail"), store, rules))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(requestContext::at).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 요청_시각은_load와_apply에서_처음_요청부터_읽을_수_없다() {
    // 다시 온 요청의 load에는 창구가 없다. 처음 요청에서도 막아야 잘못 쓴 코드가 첫 실행에서 드러난다(#94 리뷰).
    store.onLoad = requestContext::at;
    assertThatThrownBy(() -> pipeline.create(register("load"), store, rules))
        .isInstanceOf(IllegalStateException.class);
    store.onLoad = () -> {};

    Transitions<Sample> readingInApply =
        new Transitions<>() {
          @Override
          public Sample initial() {
            return rules.initial();
          }

          @Override
          public Sample apply(Sample now, ActionEntry action) {
            requestContext.at();
            return rules.apply(now, action);
          }
        };
    assertThatThrownBy(() -> pipeline.create(register("apply"), store, readingInApply))
        .isInstanceOf(IllegalStateException.class);
    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void 같은_키로_다시_온_요청은_save를_부르지_않고_load는_창구_없이_돈다() {
    List<Instant> seenAt = new ArrayList<>();
    store.afterSave = () -> seenAt.add(requestContext.at());
    PipelineResult<Sample> first = pipeline.create(register("again"), store, rules);

    List<Boolean> openInLoad = new ArrayList<>();
    store.onLoad =
        () -> {
          try {
            requestContext.at();
            openInLoad.add(true);
          } catch (IllegalStateException closed) {
            openInLoad.add(false);
          }
        };
    PipelineResult<Sample> again = pipeline.create(register("again"), store, rules);

    assertThat(again.action().replayed()).isTrue();
    assertThat(again.action().actionId()).isEqualTo(first.action().actionId());
    assertThat(seenAt).hasSize(1); // save는 처음 요청에서만
    assertThat(openInLoad).containsExactly(false);
  }

  /** 부를 때마다 1.001마이크로초씩 가는 시계. 시각을 두 번 읽거나 자르지 않으면 값이 어긋난다. */
  private static Clock advancingClock() {
    return new Clock() {
      private Instant next = Instant.parse("2026-11-17T00:00:00.000000123Z");

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        Instant now = next;
        next = next.plusNanos(1_001);
        return now;
      }
    };
  }

  @Test
  void 정산_완료된_호실에서도_공유를_그만둔_사람은_다시_공유할_수_있다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, true, false, true, true));

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.RESUME_SHARING, Map.of())
            .record(ActionType.SHARE_CONSENT, Map.of()));

    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.RESUME_SHARING, ActionType.SHARE_CONSENT);
  }

  @Test
  void 공유를_그만둔_사람도_거주_종료_요청에는_답할_수_있다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, false, true, false));

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.CONFIRM_END, Map.of()));

    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 공유를_그만둔_사람은_허용_목록_밖의_내_기록도_남길_수_없다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, false, true, false));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .record(ActionType.START_REPAIR, Map.of())))
        .hasMessage(ErrorCode.SHARING_STOPPED.name());
  }

  @Test
  void 물어본_대상과_다른_대상을_save가_돌려주면_막는다() {
    ActionTarget repairCase = pipeline.create(register("k1"), store, rules).action().target();
    StateStore<Sample> photoStore =
        new StateStore<>() {
          @Override
          public Sample load(ActionCommand command) {
            return store.load(command);
          }

          @Override
          public SavedState save(ActionCommand command, Sample next) {
            return SavedState.created(ActionTarget.photo(UUID.randomUUID())); // 부모를 물었는데 새 사진을 돌려줌
          }
        };

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k2")
                    .step(ActionType.START_REPAIR, done -> repairCase, photoStore, rules)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ActionStep.creating");
    assertThat(writer.findByRoom(room)).hasSize(1);
  }

  @Test
  void 문자_그만_받기는_호실_상태와_관계없이_남는다() {
    gates.putRoom(new RoomGate(room, null, true, true, true, true)); // 정산 완료·합류 전·공유 중단·거주 종료

    pipeline.executeAll(
        ActionBatch.of(room, Actor.link(UUID.randomUUID(), PartySide.LANDLORD), "k1")
            .record(ActionType.OPT_OUT, Map.of()));

    assertThat(writer.findByRoom(room))
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.OPT_OUT);
  }

  @Test
  void 공유를_그만둔_사람은_초대할_수_없다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, false, true, false));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .record(ActionType.INVITE, Map.of())))
        .hasMessage(ErrorCode.SHARING_STOPPED.name());
  }

  @Test
  void 합류_전_기록에서도_사진_지우기는_된다() {
    gates.put(tenantId, new RoomGate(room, PartySide.TENANT, false, true, false, false));

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
                        null,
                        false)));

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
            .creating(ActionType.REGISTER, store, rules));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .creating(ActionType.REGISTER, store, rules)
                    .record(ActionType.SHARE_CONSENT, Map.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 행동이_빠진_묶음이면_IDEMPOTENCY_KEY_REUSED() {
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .record(ActionType.SHARE_CONSENT, Map.of())
            .creating(ActionType.REGISTER, store, rules));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .record(ActionType.SHARE_CONSENT, Map.of())))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 첫_단계를_건너뛴_묶음도_다음_단계의_대상이_다르면_IDEMPOTENCY_KEY_REUSED() {
    ActionTarget first = pipeline.create(register("r1"), store, rules).action().target();
    ActionTarget second = pipeline.create(register("r2"), store, rules).action().target();
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
                .creating(ActionType.REGISTER, store, rules));
    BatchResult landlord =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(landlordId), "k1")
                .creating(ActionType.REGISTER, store, rules));

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
            .creating(ActionType.REGISTER, store, rules));

    List<ActionEntry> entries = writer.findRequest(Actor.member(tenantId).key(), "k1");
    UUID requestId = entries.getFirst().requestId();
    assertThat(requestId).isNotNull();
    assertThat(entries).allMatch(entry -> requestId.equals(entry.requestId()));
    assertThat(entries)
        .extracting(ActionEntry::idempotencyKey)
        .containsExactly("k1", requestId + "#2");
  }

  @Test
  void 다시_온_요청의_대상을_정하다_실패하면_IDEMPOTENCY_KEY_REUSED() {
    ActionTarget target = pipeline.create(register("r1"), store, rules).action().target();
    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(tenantId), "k1")
            .step(ActionType.START_REPAIR, done -> target, store, rules));

    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k1")
                    .step(
                        ActionType.START_REPAIR,
                        done -> ActionTarget.repairCase(null), // 수리 건 ID가 빠진 요청
                        store,
                        rules)))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 대상이_있던_행동에_대상_없이_다시_오면_IDEMPOTENCY_KEY_REUSED() {
    ActionTarget target = pipeline.create(register("r1"), store, rules).action().target();
    pipeline.execute(command(tenantId, ActionType.START_REPAIR, "k1", target), store, rules);

    assertThatThrownBy(() ->
            pipeline.execute(command(tenantId, ActionType.START_REPAIR, "k1", null), store, rules))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
  }

  @Test
  void 새_기록을_만드는_행동은_creating으로_열지_않으면_남기지_않는다() {
    assertThatThrownBy(() -> pipeline.execute(register("k1"), store, rules))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            pipeline.executeAll(
                ActionBatch.of(room, Actor.member(tenantId), "k2")
                    .step(ActionType.REGISTER, done -> null, store, rules)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(writer.findByRoom(room)).isEmpty();
  }

  @Test
  void 새_기록을_만드는_단계인데_save가_기록을_돌려주지_않으면_남기지_않는다() {
    StateStore<Sample> forgetful =
        new StateStore<>() {
          @Override
          public Sample load(ActionCommand command) {
            return rules.initial();
          }

          @Override
          public SavedState save(ActionCommand command, Sample next) {
            return SavedState.unchanged();
          }
        };

    assertThatThrownBy(() -> pipeline.create(register("k1"), forgetful, rules))
        .isInstanceOf(IllegalStateException.class);
    assertThat(writer.findByRoom(room)).isEmpty();
  }

  /** 한계(ActionPipeline.replay Javadoc): 처음에 건너뛴 단계는 기록이 없어, 조건부 단계만큼 구성이 다른 묶음은 같은 요청으로 본다. */
  @Test
  void 조건부_단계만큼_구성이_다른_묶음은_같은_요청으로_본다() {
    ActionTarget target = pipeline.create(register("r1"), store, rules).action().target();
    BatchResult first =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(ActionType.START_REPAIR, done -> target, store, rules));

    BatchResult other =
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(tenantId), "k1")
                .step(
                    ActionStep.of(ActionType.REQUEST_CLOSE, done -> target, store, rules)
                        .when(now -> now.status().equals("IN_PROGRESS")))
                .step(ActionType.START_REPAIR, done -> target, store, rules));

    assertThat(other.replayed()).isTrue();
    assertThat(ids(other)).containsExactlyElementsOf(ids(first));
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
        .creating(ActionType.REGISTER, store, rules)
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
    Runnable onLoad = () -> {};

    @Override
    public Sample load(ActionCommand command) {
      onLoad.run();
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
    public ActionEntry write(ActionCommand command, UUID requestId, Instant at) {
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
}
