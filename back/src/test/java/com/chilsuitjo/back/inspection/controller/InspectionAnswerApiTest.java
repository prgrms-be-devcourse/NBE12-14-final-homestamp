package com.chilsuitjo.back.inspection.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.inspection.rule.InspectionState;
import com.chilsuitjo.back.inspection.rule.InspectionStatus;
import com.chilsuitjo.back.inspection.rule.InspectionTransitions;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 4-7 점검 항목 응답·남은 항목 모두 확인·보충(#235)을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 세입자가 기준 측으로
 * 항목을 쓰고 임대인이 답한다. 점검 확인 요청은 가짜 {@code WaitService}가 받는다.
 */
@Import({TestcontainersConfiguration.class, InspectionApiTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionAnswerApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private ActionReader actions;
  @Autowired private InspectionTransitions inspectionRules;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private InspectionApiTest.MutableClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', DATE '2026-03-01', 'LIVING', 'TENANT',
                CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, (SELECT id FROM state.part_template ORDER BY sort_order LIMIT 1), '안방', '천장', 0,
               CURRENT_TIMESTAMP, 0
        """,
        part,
        room);
  }

  // ---- 응답 ----

  @Test
  void 상대가_답하면_도장이_찍히고_마지막_미응답에_답하면_확인_요청이_답함이_된다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    respond(landlord, items[0], "a1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(InspectionController.REPLAYED))
        .andExpect(jsonPath("$.targetId").value(items[0].toString()))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.state").value("ACK"));

    Map<String, Object> row = itemRow(items[0]);
    assertThat(row).containsEntry("status", "ACK");
    assertThat(row.get("confirmed_version_id")).isEqualTo(row.get("current_version_id"));
    // 아직 답하지 않은 항목이 남아 요청은 열려 있다
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);

    // 보류도 답이라 마지막 미응답이 보류여도 요청이 닫힌다(#235)
    respond(landlord, items[1], "a2", "NEED_MORE", "사진이 흐려요", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NEED_MORE"));
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM record.action WHERE inspection_item_id = ?"
                    + " AND type = 'RESPOND_ITEM'",
                String.class,
                items[1]))
        .isEqualTo("사진이 흐려요");
  }

  @Test
  void 확인_없음_항목도_미응답으로_세어_답하기_전에는_요청이_닫히지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    jdbc.update("UPDATE state.inspection_item SET status = 'UNCONFIRMED' WHERE id = ?", items[1]);
    waits.markUnconfirmed(target(inspection), RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    respond(landlord, items[0], "a1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.UNCONFIRMED);

    respond(landlord, items[1], "a2", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACK"));
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 같은_키로_다시_보내면_처음_결과를_돌려준다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "ACK", null, 1).andExpect(status().isOk());

    respond(landlord, item, "a1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.state").value("ACK"));
    assertThat(actions(item, "RESPOND_ITEM")).isEqualTo(1);
  }

  @Test
  void 작성_중이면_INVALID_STATE이고_기준_측은_자기_항목에_답할_수_없다() throws Exception {
    UUID inspection = created(start());
    UUID item = created(addItem(inspection, "i1"));

    respond(landlord, item, "draft", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    requestConfirm(inspection);
    respond(tenant, item, "own", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    respond(landlord, item, "stale", "ACK", null, 2)
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    respond(landlord, item, "no-reason", "DISPUTE", " ", 1)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    assertThat(itemRow(item)).containsEntry("status", "PENDING");
  }

  @Test
  void 당사자가_아니거나_없는_항목이면_NOT_FOUND() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];

    respond(member(), item, "x", "ACK", null, 1).andExpect(status().isNotFound());
    respond(landlord, UUID.randomUUID(), "y", "ACK", null, 1).andExpect(status().isNotFound());
    confirmAll(member(), inspection, "z", seen(item)).andExpect(status().isNotFound());
    assertThat(itemRow(item)).containsEntry("status", "PENDING");
  }

  @Test
  void 불변식_18_기준_측이_공유를_그만둔_시각에_작성_중이던_항목에는_답할_수_없다() throws Exception {
    UUID inspection = created(start());
    UUID item = created(addItem(inspection, "i1"));
    stopSharing(PartySide.TENANT, Instant.now());
    clock.advance(Duration.ofDays(7));
    // 상대가 "지금까지 쓴 것으로" 보내도 그만둔 시각에 작성 중이던 항목은 드러나지 않는다(#218)
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "stale"))
        .andExpect(status().isOk());

    respond(landlord, item, "hidden", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    confirmAll(landlord, inspection, "hidden-all", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
  }

  // ---- 남은 항목 모두 확인 ----

  @Test
  void 모두_확인은_본_항목마다_맞아요를_남기고_확인_요청을_닫는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", hasItem("confirm-all")))
        .andExpect(jsonPath("$.items[0].availableActions", hasItem("respond")));

    // 모든 항목이 답을 받아 같은 요청에서 잠긴다(INSP-1b)
    confirmAll(landlord, inspection, "all", seen(items))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(inspection.toString()))
        .andExpect(jsonPath("$.status").value("LOCKED"));

    assertThat(itemRow(items[0])).containsEntry("status", "ACK");
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
    assertThat(itemRow(items[1]).get("confirmed_version_id"))
        .isEqualTo(itemRow(items[1]).get("current_version_id"));
    assertThat(actions(items[0], "RESPOND_ITEM") + actions(items[1], "RESPOND_ITEM")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'CONFIRM_ALL'",
                Long.class,
                inspection))
        .isEqualTo(1);
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);

    confirmAll(landlord, inspection, "all", seen(items))
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
  }

  @Test
  void 모두_확인은_보류_이견을_빼고_답하지_않은_항목만_대상이다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    respond(landlord, items[0], "a1", "DISPUTE", "사실과 달라요", 1).andExpect(status().isOk());

    // 이견 항목을 함께 보내면 지금 대상과 달라 VERSION_CONFLICT
    confirmAll(landlord, inspection, "with-dispute", seen(items))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    confirmAll(landlord, inspection, "rest", seen(items[1])).andExpect(status().isOk());
    assertThat(itemRow(items[0])).containsEntry("status", "DISPUTE");
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
  }

  @Test
  void 본_판이_지금과_다르면_아무것도_남지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    confirmAll(landlord, inspection, "partial", seen(items[0]))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    confirmAll(
            landlord,
            inspection,
            "old",
            "[{\"id\":\""
                + items[0]
                + "\",\"seenVersionNo\":1},{\"id\":\""
                + items[1]
                + "\",\"seenVersionNo\":2}]")
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    assertThat(itemRow(items[0])).containsEntry("status", "PENDING");
    assertThat(actions(items[0], "RESPOND_ITEM")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'CONFIRM_ALL'",
                Long.class,
                inspection))
        .isZero();
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);
  }

  @Test
  void 항목이_없는_점검은_하자_기록_없음으로_한_번_확인해_요청을_닫는다() throws Exception {
    UUID inspection = created(start());
    requestConfirm(inspection);
    detail(tenant, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));

    confirmAll(tenant, inspection, "own", "[]")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    confirmAll(landlord, inspection, "none", "[]").andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);

    confirmAll(landlord, inspection, "again", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // ---- 잠금(INSP-1b·1c·1d, #326) ----

  @Test
  void 실제_응답으로_잠근_행과_저장된_행동의_재계산_상태가_같다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    respond(landlord, items[0], "replay-ack", "ACK", null, 1).andExpect(status().isOk());
    respond(landlord, items[1], "replay-dispute", "DISPUTE", "사실과 달라요", 1)
        .andExpect(status().isOk());

    assertReplayedLock(inspection);
  }

  @Test
  void 실제_모두_확인으로_잠근_행과_저장된_행동의_재계산_상태가_같다() throws Exception {
    UUID inspection = requestedWith(2);

    confirmAll(landlord, inspection, "replay-all", seen(items(inspection)))
        .andExpect(status().isOk());

    assertReplayedLock(inspection);
  }

  @Test
  void 실제_하자_기록_없음_확인으로_잠근_행과_저장된_행동의_재계산_상태가_같다() throws Exception {
    UUID inspection = requestedWith(0);

    confirmAll(landlord, inspection, "replay-empty", "[]").andExpect(status().isOk());

    assertReplayedLock(inspection);
  }

  private void assertReplayedLock(UUID inspection) {
    // 호실의 실제 행동을 room_seq 순서로 읽고, 이 점검의 상태를 옮기는 행동만 접는다.
    // ActionReader는 MANDATORY이므로 읽기도 트랜잭션 안에서 한다.
    List<ActionEntry> history =
        transactions.execute(ignored ->
            actions.findByRoom(room).stream()
                .filter(action -> ActionTarget.inspection(inspection).equals(action.target()))
                .filter(action -> InspectionTransitions.CHANGES.contains(action.type()))
                .toList());
    assertThat(history).isNotNull();
    assertThat(history)
        .extracting(ActionEntry::type)
        .containsExactly(
            ActionType.START_INSPECTION, ActionType.REQUEST_INSPECTION_CONFIRM, ActionType.LOCK);
    ActionEntry lock = history.getLast();
    assertThat(lock.actorKey()).isEqualTo("server");
    assertThat(lock.actorSide()).isNull();
    InspectionState replayed = Replay.fold(inspectionRules, history);
    assertThat(replayed.status()).isEqualTo(InspectionStatus.LOCKED);
    assertThat(inspectionRow(inspection)).containsEntry("status", replayed.status().name());
  }

  @Test
  void INSP_1b_모든_항목이_정리되면_잠긴다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    respond(landlord, items[0], "a1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    assertThat(lockActions(inspection)).isZero();

    // 이견도 답이라 마지막 답으로 같은 요청에서 잠긴다
    respond(landlord, items[1], "a2", "DISPUTE", "사실과 달라요", 1).andExpect(status().isOk());

    Map<String, Object> row = inspectionRow(inspection);
    assertThat(row).containsEntry("status", "LOCKED").containsEntry("lock_reason", "ALL_RESOLVED");
    assertThat(row.get("locked_at")).isNotNull();
    Map<String, Object> lock =
        jdbc.queryForMap(
            "SELECT actor_key, actor_side, payload::text AS payload, request_id"
                + " FROM record.action WHERE inspection_id = ? AND type = 'LOCK'",
            inspection);
    assertThat(lock).containsEntry("actor_key", "server");
    assertThat(lock.get("actor_side")).isNull();
    assertThat(lock.get("payload").toString()).contains("\"lockReason\"", "\"ALL_RESOLVED\"");
    // 마지막 응답과 한 요청으로 남는다(딸린 행동)
    assertThat(lock.get("request_id"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT request_id FROM record.action WHERE inspection_item_id = ?"
                    + " AND type = 'RESPOND_ITEM'",
                UUID.class,
                items[1]));
    detail(tenant, inspection)
        .andExpect(jsonPath("$.status").value("LOCKED"))
        .andExpect(jsonPath("$.lockReason").value("ALL_RESOLVED"))
        .andExpect(jsonPath("$.lockedAt").exists());
  }

  @Test
  void INSP_1b_상대_미확인_항목도_잠금_조건을_채우고_도장이_찍힌다() throws Exception {
    // 03 H: 확인·보류·이견·상대 미확인 중 하나면 잠긴다. 미확인 표시는 기다림 타이머 몫이라 행으로 둔다(#326 선행 의존성)
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    jdbc.update("UPDATE state.inspection_item SET status = 'UNCONFIRMED' WHERE id = ?", items[1]);
    waits.markUnconfirmed(target(inspection), RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    respond(landlord, items[0], "a1", "ACK", null, 1).andExpect(status().isOk());

    assertThat(inspectionRow(inspection))
        .containsEntry("status", "LOCKED")
        .containsEntry("lock_reason", "ALL_RESOLVED");
    // 잠기면 미확인 항목도 도장 뒤(03 G "점검 전체")
    assertThat(itemRow(items[1]).get("confirmed_version_id"))
        .isEqualTo(itemRow(items[1]).get("current_version_id"));
    // 요청은 미확인 항목이 남아 열려 있다(늦은 답을 받음, 요청이 닫히는 조건과 잠금 조건은 다르다)
    assertThat(confirmRequest(inspection)).contains(RequestStatus.UNCONFIRMED);

    // 잠긴 뒤 늦은 답과 잠긴 뒤 추가에도 잠금은 그대로이고 다시 잠그지 않는다
    respond(landlord, items[1], "late", "ACK", null, 1).andExpect(status().isOk());
    created(addItemAs(landlord, inspection, "after-lock", "[]"));
    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");
    assertThat(lockActions(inspection)).isEqualTo(1);
  }

  @Test
  void 잠금을_일으킨_응답을_같은_키로_다시_보내도_다시_잠그지_않는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "ACK", null, 1).andExpect(status().isOk());

    respond(landlord, item, "a1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"));

    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");
    assertThat(lockActions(inspection)).isEqualTo(1);
  }

  @Test
  void 모두_확인은_중간_단계에서_잠겨도_남은_항목에_답하고_한_번만_잠근다() throws Exception {
    // 앞 항목에 답하면 남은 항목이 상대 미확인뿐이라 첫 응답 단계에서 잠기고, 다음 단계는 잠긴 뒤의 늦은 답이다
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    jdbc.update("UPDATE state.inspection_item SET status = 'UNCONFIRMED' WHERE id = ?", items[1]);

    confirmAll(landlord, inspection, "all", seen(items))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LOCKED"));

    assertThat(itemRow(items[0])).containsEntry("status", "ACK");
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
    assertThat(lockActions(inspection)).isEqualTo(1);
  }

  @Test
  void 퇴거_점검은_아직_잠그지_않는다() throws Exception {
    // #326 범위는 입주 점검. 퇴거 점검은 거주 종료와 따로 잠기는 규칙과 함께 후속에서 다룬다
    UUID inspection =
        created(
            mvc.perform(
                post("/api/rooms/{id}/inspections", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "start-out")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"MOVE_OUT\"}")));
    created(addItem(inspection, "out"));
    requestConfirm(inspection);
    UUID item = items(inspection)[0];

    respond(landlord, item, "a1", "ACK", null, 1).andExpect(status().isOk());

    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    assertThat(lockActions(inspection)).isZero();
  }

  @Test
  void INSP_1c_항목이_없으면_하자_기록_없음_확인으로_잠긴다() throws Exception {
    UUID inspection = created(start());
    requestConfirm(inspection);

    confirmAll(landlord, inspection, "none", "[]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LOCKED"));

    assertThat(inspectionRow(inspection)).containsEntry("lock_reason", "NO_ITEMS");
    assertThat(lockActions(inspection)).isEqualTo(1);
    detail(landlord, inspection).andExpect(jsonPath("$.lockReason").value("NO_ITEMS"));
  }

  @Test
  void INSP_1d_상대가_더한_항목에_답이_없으면_아직_잠기지_않는다() throws Exception {
    UUID inspection = created(start());
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    UUID mine = created(addItem(inspection, "mine"));
    requestConfirm(inspection);

    respond(landlord, mine, "a-mine", "ACK", null, 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");

    respond(tenant, theirs, "a-theirs", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection))
        .containsEntry("status", "LOCKED")
        .containsEntry("lock_reason", "ALL_RESOLVED");
  }

  @Test
  void 작성_중에는_모든_항목에_답해도_잠기지_않는다() throws Exception {
    UUID inspection = created(start());
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));

    respond(tenant, theirs, "a1", "ACK", null, 1).andExpect(status().isOk());

    assertThat(inspectionRow(inspection)).containsEntry("status", "DRAFT");
    assertThat(lockActions(inspection)).isZero();
  }

  @Test
  void 기준_측_항목_없이_상대_항목만_있으면_하자_기록_없음_확인_뒤_상대_항목에_답할_때_잠긴다() throws Exception {
    // #392 논의 6: 확인을 먼저 하고 상대 항목에 나중에 답하는 순서. 항목이 있으니 ALL_RESOLVED
    UUID inspection = created(start());
    requestConfirm(inspection);
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    confirmAll(landlord, inspection, "none", "[]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    respond(tenant, theirs, "a1", "DISPUTE", "사실과 달라요", 1).andExpect(status().isOk());

    assertThat(inspectionRow(inspection))
        .containsEntry("status", "LOCKED")
        .containsEntry("lock_reason", "ALL_RESOLVED");
    assertThat(lockActions(inspection)).isEqualTo(1);
    assertThat(itemRow(theirs).get("confirmed_version_id")).isNotNull();
    detail(landlord, inspection).andExpect(jsonPath("$.lockReason").value("ALL_RESOLVED"));
  }

  @Test
  void 기준_측_항목_없이_상대_항목만_있으면_상대_항목에_먼저_답해도_하자_기록_없음_확인으로_잠긴다() throws Exception {
    UUID inspection = created(start());
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    respond(tenant, theirs, "a1", "ACK", null, 1).andExpect(status().isOk());
    requestConfirm(inspection);
    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");

    confirmAll(landlord, inspection, "none", "[]")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LOCKED"));

    assertThat(inspectionRow(inspection)).containsEntry("lock_reason", "ALL_RESOLVED");
    assertThat(lockActions(inspection)).isEqualTo(1);
    assertReplayedLock(inspection);
  }

  @Test
  void 기준_측_항목_없이_상대_항목만_있으면_하자_기록_없음_확인_전에는_잠기지_않는다() throws Exception {
    UUID inspection = created(start());
    requestConfirm(inspection);
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));

    respond(tenant, theirs, "a1", "ACK", null, 1).andExpect(status().isOk());

    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    assertThat(lockActions(inspection)).isZero();
  }

  @Test
  void 불변식_18_보이는_범위를_고정한_뒤의_잠금은_그만둔_사람에게_보이지_않는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    stopSharing(PartySide.TENANT, Instant.now());

    respond(landlord, item, "a1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");

    detail(tenant, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.lockedAt").doesNotExist())
        .andExpect(jsonPath("$.lockReason").doesNotExist());
  }

  @Test
  void 불변식_18_공유를_그만둔_뒤_상대의_응답은_그만둔_사람의_항목에_보이지_않고_답한_사람에게는_보인다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    // 그만두기 전의 응답은 그만둔 뒤에도 보인다
    respond(landlord, items[0], "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].status").value("NEED_MORE"))
        .andExpect(jsonPath("$.items[0].availableActions", hasItem("supplement")));
    stopSharing(PartySide.TENANT, clock.instant());
    clock.advance(Duration.ofSeconds(1));

    respond(landlord, items[1], "a2", "ACK", null, 1).andExpect(status().isOk());
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");

    // 그만둔 사람: 점검 상태와 같은 시각 기준(#363). 점검 행동은 모두 SHARING_STOPPED라 버튼도 없다
    detail(tenant, inspection)
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].status").value("NEED_MORE"))
        .andExpect(jsonPath("$.items[1].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].availableActions.length()").value(0))
        .andExpect(jsonPath("$.items[1].availableActions.length()").value(0))
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
    // 답한 사람에게는 자기 응답이 보인다
    detail(landlord, inspection)
        .andExpect(jsonPath("$.items[0].status").value("NEED_MORE"))
        .andExpect(jsonPath("$.items[1].status").value("ACK"));
  }

  @Test
  void 불변식_18_공유를_그만둔_뒤_상대의_보충은_그만둔_사람의_항목에_보이지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    stopSharing(PartySide.LANDLORD, clock.instant());
    clock.advance(Duration.ofSeconds(1));

    supplement(tenant, item, "s1", "{\"description\":\"밝게 다시 찍었어요\"}").andExpect(status().isOk());
    assertThat(itemRow(item)).containsEntry("status", "PENDING");

    // 그만둔 사람: 보충 전 보류 그대로이고 다음 보충 시각도 없다. 답하기 버튼도 없다(SHARING_STOPPED)
    detail(landlord, inspection)
        .andExpect(jsonPath("$.items[0].status").value("NEED_MORE"))
        .andExpect(jsonPath("$.items[0].nextRerequestAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].availableActions.length()").value(0))
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
    // 보충한 사람에게는 보인다
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].nextRerequestAt").exists());
  }

  @Test
  void 불변식_18_공유_중단_뒤_남은_쪽이_시작하고_확인_요청한_점검은_그_사람에게_그대로_보인다() throws Exception {
    stopSharing(PartySide.TENANT, clock.instant());
    clock.advance(Duration.ofSeconds(1));
    UUID inspection =
        created(
            mvc.perform(
                post("/api/rooms/{id}/inspections", room)
                    .with(loginAs(landlord))
                    .header("Idempotency-Key", "start-after-stop")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"MOVE_IN\"}")));
    UUID item = created(addItemAs(landlord, inspection, "item-after-stop", "[]"));
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "request-after-stop"))
        .andExpect(status().isOk());

    // 고정한 시각에 없던 점검이라도 시작한 사람에게는 자기 행동으로 다시 계산한 그대로 보인다
    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.authorSide").value("LANDLORD"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].version.no").value(1));
    // 그만둔 사람에게는 있는지도 알 수 없다
    detail(tenant, inspection).andExpect(status().isNotFound());
  }

  @Test
  void 불변식_18_퇴거_나란히_보기의_입주_항목도_입주_점검_상세와_같은_시각_기준이다() throws Exception {
    UUID moveIn = requestedWith(2);
    UUID[] items = items(moveIn);
    UUID moveOut = created(startMoveOut());
    stopSharing(PartySide.TENANT, clock.instant());
    clock.advance(Duration.ofSeconds(1));

    respond(landlord, items[1], "a1", "ACK", null, 1).andExpect(status().isOk());

    detail(tenant, moveOut)
        .andExpect(jsonPath("$.sideBySide.moveIn.status").value("REQUESTED"))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems[0].status").value("PENDING"))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems[1].status").value("PENDING"));
    detail(landlord, moveOut)
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems[1].status").value("ACK"));
  }

  // ---- 보충 ----

  @Test
  void 보류_뒤_보충하면_확인_기다림으로_돌아가고_확인_요청이_다시_열린다() throws Exception {
    // 보류도 답이라 모든 항목에 답하면 잠겨 보충할 수 없다(03 H). 다른 항목 하나는 확인 기다림으로 둔다
    UUID inspection = requestedWith(2);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].availableActions", hasItem("supplement")))
        .andExpect(jsonPath("$.items[0].nextRerequestAt").doesNotExist());

    supplement(tenant, item, "s1", "{\"description\":\"밝게 다시 찍었어요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"))
        .andExpect(jsonPath("$.versionNo").value(1));

    assertThat(itemRow(item)).containsEntry("status", "PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM record.supplement WHERE inspection_item_id = ?",
                String.class,
                item))
        .isEqualTo("밝게 다시 찍었어요");
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);
    detail(tenant, inspection).andExpect(jsonPath("$.items[0].nextRerequestAt").exists());
  }

  @Test
  void 보충은_근거가_필요하고_7일에_한_번이다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(tenant, item, "empty", "{}")
        .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    supplement(landlord, item, "other", "{\"description\":\"보충\"}")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    supplement(tenant, item, "s1", "{\"description\":\"보충\"}").andExpect(status().isOk());

    respond(landlord, item, "a2", "NEED_MORE", "아직 흐려요", 1).andExpect(status().isOk());
    supplement(tenant, item, "s2", "{\"description\":\"다시 보충\"}")
        .andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    clock.advance(Duration.ofDays(7));
    supplement(tenant, item, "s3", "{\"description\":\"다시 보충\"}").andExpect(status().isOk());
  }

  @Test
  void 상대가_공유를_그만뒀으면_보충해도_확인_요청을_열지_않는다() throws Exception {
    // 상대가 더한 항목이 확인 기다림으로 남아 점검은 잠기지 않고, 기준 측 방향 요청만 답함으로 닫힌다
    UUID inspection = created(start());
    UUID mine = created(addItem(inspection, "mine"));
    created(addItemAs(landlord, inspection, "other", "[]"));
    requestConfirm(inspection);
    respond(landlord, mine, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    stopSharing(PartySide.LANDLORD, Instant.now());

    supplement(tenant, mine, "s1", "{\"description\":\"보충\"}").andExpect(status().isOk());

    // 그만둔 사람에게는 새 확인 요청을 만들지 않는다(03 B, #218과 같은 기준)
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 잠긴_뒤에는_보충이_INVALID_STATE이고_늦은_응답은_된다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    respond(landlord, items[0], "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    // 확인 기다림 항목이 남은 채로 잠긴 점검을 만들려고 행으로 잠근다(늦은 답을 보려고, 03 H "잠긴 뒤", #272 리뷰)
    jdbc.update(
        "UPDATE state.inspection SET status = 'LOCKED', locked_at = now(),"
            + " lock_reason = 'ALL_RESOLVED' WHERE id = ?",
        inspection);

    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].availableActions", not(hasItem("supplement"))));
    supplement(tenant, items[0], "late-s", "{\"description\":\"보충\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(itemRow(items[0])).containsEntry("status", "NEED_MORE");
    assertThat(actions(items[0], "SUPPLEMENT_ITEM")).isZero();

    respond(landlord, items[1], "late-a", "ACK", null, 1).andExpect(status().isOk());
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
  }

  @Test
  void 응답_보충_입력은_pipeline_전에_막는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];

    mvc.perform(
            post("/api/inspection-items/{id}/respond", item)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "no-answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenVersionNo\":1}"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    respond(landlord, item, "rrn", "DISPUTE", "900101-1234567", 1)
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    StringBuilder tooMany = new StringBuilder("[");
    for (int i = 0; i < 11; i++) {
      tooMany.append(i == 0 ? "" : ",").append("\"t").append(i).append('"');
    }
    supplement(tenant, item, "many", "{\"uploadTickets\":" + tooMany.append(']') + "}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "blank", "{\"uploadTickets\":[\" \"]}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "long", "{\"description\":\"" + "가".repeat(2001) + "\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "rrn", "{\"description\":\"900101-1234567\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(actions(item, "RESPOND_ITEM") + actions(item, "SUPPLEMENT_ITEM")).isZero();
  }

  // ---- 빠뜨린 하자 더하기(INSP-1d·1e, #286) ----

  @Test
  void INSP_1d_작성_중_상대가_더하면_그_방향_요청이_열리고_기준_측이_바로_답한다() throws Exception {
    UUID inspection = created(start());
    UUID mine = created(addItem(inspection, "mine"));

    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));

    // 요청한 측 = 더한 측. 세입자(기준 측)의 작성 중 항목은 확인 요청 때 함께라 그 방향 요청은 아직 없다
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    assertThat(confirmRequest(inspection)).isEmpty();
    detail(tenant, inspection)
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(
            jsonPath("$.items[?(@.id == '" + theirs + "')].availableActions[0]").value("respond"));
    // 상대에게 기준 측 작성 중 항목은 보이지 않아 답할 수 없다
    respond(landlord, mine, "hidden", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    respond(tenant, theirs, "a1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(itemRow(theirs)).containsEntry("status", "ACK");
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.ANSWERED);
    assertThat(inspectionRow(inspection)).containsEntry("status", "DRAFT");
  }

  @Test
  void 두_방향_확인_요청은_따로_열리고_한쪽이_답해도_다른_쪽은_남는다() throws Exception {
    UUID inspection = created(start());
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    UUID mine = created(addItem(inspection, "mine"));
    requestConfirm(inspection);

    // 점검 × 방향마다 하나(불변식 21): 상대가 더한 쪽과 기준 측 확인 요청이 함께 기다린다
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);

    respond(landlord, mine, "a-mine", "ACK", null, 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.ANSWERED);
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);

    respond(tenant, theirs, "a-theirs", "ACK", null, 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 작성_중_모두_확인은_상대_항목만이고_하자_기록_없음은_확인_요청_뒤에만() throws Exception {
    UUID inspection = created(start());
    confirmAll(landlord, inspection, "none-draft", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));

    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    detail(tenant, inspection).andExpect(jsonPath("$.availableActions", hasItem("confirm-all")));
    // 상대는 답할 기준 측 항목이 없고(작성 중이라 가려짐) 자기 항목에는 답하지 않는다
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
    confirmAll(landlord, inspection, "own", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    confirmAll(tenant, inspection, "all", seen(theirs)).andExpect(status().isOk());
    assertThat(itemRow(theirs)).containsEntry("status", "ACK");
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 상대가_더한_항목만_있으면_상대가_하자_기록_없음으로_기준_측_요청을_닫는다() throws Exception {
    // #291 리뷰: 기준 측 항목 0개에 상대만 더한 점검. 상대 자신의 항목은 "하자 기록 없음" 판단에 세지 않는다
    UUID inspection = created(start());
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    respond(tenant, theirs, "a1", "ACK", null, 1).andExpect(status().isOk());
    requestConfirm(inspection);
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);

    detail(landlord, inspection).andExpect(jsonPath("$.availableActions", hasItem("confirm-all")));
    confirmAll(landlord, inspection, "none", "[]").andExpect(status().isOk());
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 확인_요청_중_상대가_먼저_더해도_하자_기록_없음을_확인할_수_있다() throws Exception {
    UUID inspection = created(start());
    requestConfirm(inspection);
    UUID theirs = created(addItemAs(landlord, inspection, "other", "[]"));
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);

    confirmAll(landlord, inspection, "none", "[]").andExpect(status().isOk());
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.ANSWERED);
    // 상대가 더한 쪽 요청은 기준 측이 답할 때까지 남는다
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    // 기준 측 항목이 있으면 그대로 "하자 기록 없음"이 아니다(기준 측은 상대 항목에 답해야 한다)
    confirmAll(tenant, inspection, "tenant-none", "[]")
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    confirmAll(tenant, inspection, "tenant-all", seen(theirs)).andExpect(status().isOk());
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void INSP_1e_잠긴_뒤_추가는_표시가_남고_잠금과_보충_제한은_그대로다() throws Exception {
    UUID inspection = requestedWith(1);
    lock(inspection);

    UUID late = created(addItemAs(landlord, inspection, "late", "[]"));

    assertThat(itemRow(late))
        .containsEntry("added_after_lock", true)
        .containsEntry("added_after_end", false);
    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[?(@.id == '" + late + "')].addedAfterLock").value(true));

    respond(tenant, late, "need", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    assertThat(inspectionRow(inspection)).containsEntry("status", "LOCKED");
    // 잠긴 뒤 보충은 그대로 막힌다(#272)
    supplement(landlord, late, "s1", "{\"description\":\"보충\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 거주_종료_뒤_추가는_표시가_남고_사진도_붙고_요청이_열린다() throws Exception {
    UUID inspection = requestedWith(1);
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    UUID after = created(addItemAs(landlord, inspection, "after-end", tickets(1)));

    assertThat(itemRow(after)).containsEntry("added_after_end", true);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.items[?(@.id == '" + after + "')].addedAfterEnd").value(true))
        .andExpect(jsonPath("$.items[?(@.id == '" + after + "')].photos.length()").value(1));
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
  }

  @Test
  void 꺼_둔_설비_가전에는_항목을_더할_수_없다() throws Exception {
    UUID inspection = requestedWith(1);
    jdbc.update("UPDATE state.room_part SET enabled = false WHERE id = ?", part);

    // 켜고 끔은 이 집에 그 설비가 있나라서 새 기록을 받지 않는다(decisions 2026-11-26 #182, 점검 항목 쪽은 A)
    addItemAs(landlord, inspection, "off", "[]")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void 공유_중단과_정산_완료에서의_항목_더하기() throws Exception {
    UUID inspection = requestedWith(1);
    // 기준 측(세입자)이 그만뒀으면 상대가 더한 항목은 요청을 열지 않고 그만둔 사람에게 보이지 않는다(불변식 18)
    stopSharing(PartySide.TENANT, Instant.now());
    clock.advance(Duration.ofSeconds(1));
    UUID unseen = created(addItemAs(landlord, inspection, "after-stop", "[]"));
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).isEmpty();
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[?(@.id == '" + unseen + "')]").isEmpty());
    // 그만둔 본인은 더할 수 없다(4-10)
    addItemAs(tenant, inspection, "own-stop", "[]")
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));

    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
    addItemAs(landlord, inspection, "settled", "[]").andExpect(jsonPath("$.code").value("SETTLED"));
  }

  // ---- 도우미 ----

  /** 세입자가 항목 {@code count}개를 쓰고 확인 요청을 보낸 점검. */
  private UUID requestedWith(int count) throws Exception {
    UUID inspection = created(start());
    for (int i = 0; i < count; i++) {
      created(addItem(inspection, "i" + i));
    }
    requestConfirm(inspection);
    return inspection;
  }

  private UUID[] items(UUID inspection) {
    return jdbc.queryForList(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ? ORDER BY id",
            UUID.class,
            inspection)
        .toArray(UUID[]::new);
  }

  private static String seen(UUID... items) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < items.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"id\":\"")
          .append(items[i])
          .append("\",\"seenVersionNo\":1}");
    }
    return json.append(']').toString();
  }

  private ResultActions start() throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "start")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"MOVE_IN\"}"));
  }

  private ResultActions startMoveOut() throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "start-out")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"MOVE_OUT\"}"));
  }

  private ResultActions addItem(UUID inspection, String key) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\"벽지 찢어짐\",\"uploadTickets\":[]}"));
  }

  private ResultActions addItemAs(UUID member, UUID inspection, String key, String tickets)
      throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\"방충망 찢어짐\",\"uploadTickets\":"
                    + tickets
                    + "}"));
  }

  private static String tickets(int count) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    return json.append(']').toString();
  }

  /** 응답 순서와 상관없이 잠긴 점검을 만든다(행으로 잠금, 서버 행동 {@code LOCK}은 남지 않음). 실제 잠금은 "잠금" 절의 테스트가 본다. */
  private void lock(UUID inspection) {
    jdbc.update(
        "UPDATE state.inspection SET status = 'LOCKED', locked_at = now(),"
            + " lock_reason = 'ALL_RESOLVED' WHERE id = ?",
        inspection);
  }

  private long lockActions(UUID inspection) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'LOCK'",
        Long.class,
        inspection);
  }

  private Map<String, Object> inspectionRow(UUID inspection) {
    return jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", inspection);
  }

  private void requestConfirm(UUID inspection) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "request"))
        .andExpect(status().isOk());
  }

  private ResultActions respond(
      UUID member, UUID item, String key, String answer, String reason, Integer seen)
      throws Exception {
    String body =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":"
            + seen
            + "}";
    return mvc.perform(
        post("/api/inspection-items/{id}/respond", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions confirmAll(UUID member, UUID inspection, String key, String seenJson)
      throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/confirm-all", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":" + seenJson + "}"));
  }

  private ResultActions supplement(UUID member, UUID item, String key, String body)
      throws Exception {
    return mvc.perform(
        post("/api/inspection-items/{id}/supplement", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions detail(UUID member, UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(member)));
  }

  private Optional<RequestStatus> confirmRequest(UUID inspection) {
    return confirmRequest(inspection, PartySide.TENANT);
  }

  /** 그 방향(요청한 측)의 점검 확인 요청 상태. */
  private Optional<RequestStatus> confirmRequest(UUID inspection, PartySide requestedBy) {
    return waits.statusOf(target(inspection), RequestKind.INSPECTION_CONFIRM, requestedBy);
  }

  private static RequestTarget target(UUID inspection) {
    return RequestTarget.of(TargetKind.INSPECTION, inspection);
  }

  private Map<String, Object> itemRow(UUID item) {
    return jdbc.queryForMap("SELECT * FROM state.inspection_item WHERE id = ?", item);
  }

  private long actions(UUID item, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE inspection_item_id = ? AND type = ?",
        Long.class,
        item,
        type);
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private void seat(PartySide side, UUID memberId) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        memberId);
  }

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }
}
