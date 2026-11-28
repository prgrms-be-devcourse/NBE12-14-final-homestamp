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
import com.chilsuitjo.back.global.common.ClosedReason;
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
 * 06 4-7 혼자 잠그기({@code lock-alone}, INSP-1g, #392)를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL, V14)까지 띄워
 * 본다. 세입자가 기준 측으로 점검을 쓰고, 집주인 자리는 테스트마다 정한다. 혼자 진행 판단은 가짜 {@code WaitService}가 진짜 {@code SoloRule}로
 * 한다.
 */
@Import({TestcontainersConfiguration.class, InspectionApiTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionLockAloneApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private ActionReader actions;
  @Autowired private InspectionTransitions inspectionRules;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private InspectionApiTest.MutableClock clock;

  private UUID room;
  private UUID tenant;
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
    connectedSeat(PartySide.TENANT, tenant);
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

  @Test
  void INSP_1g_상대가_없으면_혼자_잠그고_항목은_그대로_도장이_찍힌다() throws Exception {
    seatWithoutMember(PartySide.LANDLORD, "EMPTY", null);
    UUID inspection = requestedWith(2);
    detail(inspection).andExpect(jsonPath("$.availableActions", hasItem("lock-alone")));

    lockAlone(inspection, "solo")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(InspectionController.REPLAYED))
        .andExpect(jsonPath("$.id").value(inspection.toString()))
        .andExpect(jsonPath("$.status").value("LOCKED"));

    Map<String, Object> row = inspectionRow(inspection);
    assertThat(row).containsEntry("status", "LOCKED").containsEntry("lock_reason", "SOLO");
    assertThat(row.get("locked_at")).isNotNull();
    // 남은 확인 기다림 항목은 상태 그대로 지금 판에 도장(03 G "점검 전체")
    for (Map<String, Object> item :
        jdbc.queryForList(
            "SELECT status, confirmed_version_id FROM state.inspection_item"
                + " WHERE inspection_id = ?",
            inspection)) {
      assertThat(item).containsEntry("status", "PENDING");
      assertThat(item.get("confirmed_version_id")).isNotNull();
    }
    Map<String, Object> lock =
        jdbc.queryForMap(
            "SELECT actor_key, actor_side, payload::text AS payload"
                + " FROM record.action WHERE inspection_id = ? AND type = 'LOCK'",
            inspection);
    assertThat(lock).containsEntry("actor_key", tenant.toString());
    assertThat(lock).containsEntry("actor_side", "TENANT");
    assertThat(lock.get("payload").toString())
        .contains("\"lockReason\"", "\"SOLO\"", "\"solo_reason\"", "\"NO_COUNTERPART\"");
    // 응답 요청은 새로 열지 않는다
    assertThat(
            waits.statusOf(
                RequestTarget.of(TargetKind.INSPECTION, inspection),
                RequestKind.INSPECTION_CONFIRM,
                PartySide.TENANT))
        .isEmpty();
    detail(inspection)
        .andExpect(jsonPath("$.status").value("LOCKED"))
        .andExpect(jsonPath("$.lockReason").value("SOLO"))
        .andExpect(jsonPath("$.availableActions", not(hasItem("lock-alone"))));
    assertReplayedLock(inspection);
  }

  @Test
  void 번호만_있는_자리도_상대_없음이고_항목이_없어도_SOLO다() throws Exception {
    seatWithoutMember(PartySide.LANDLORD, "NUMBER_ONLY", "01012345678");
    UUID inspection = requestedWith(0);

    lockAlone(inspection, "solo").andExpect(status().isOk());

    assertThat(inspectionRow(inspection)).containsEntry("lock_reason", "SOLO");
    assertThat(lockPayload(inspection)).contains("\"NO_COUNTERPART\"");
  }

  @Test
  void 같은_키로_다시_보내면_처음_결과를_돌려주고_다시_잠그지_않는다() throws Exception {
    seatWithoutMember(PartySide.LANDLORD, "EMPTY", null);
    UUID inspection = requestedWith(1);
    lockAlone(inspection, "solo").andExpect(status().isOk());

    lockAlone(inspection, "solo")
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.status").value("LOCKED"));

    assertThat(lockActions(inspection)).isEqualTo(1);
    // 다른 키로 다시 누르면 이미 잠겨 INVALID_STATE
    lockAlone(inspection, "again")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 연결된_상대에게_확인_요청이_기다리는_중이면_SOLO_NOT_ALLOWED() throws Exception {
    connectedSeat(PartySide.LANDLORD, member());
    UUID inspection = requestedWith(1);
    assertThat(
            waits.statusOf(
                RequestTarget.of(TargetKind.INSPECTION, inspection),
                RequestKind.INSPECTION_CONFIRM,
                PartySide.TENANT))
        .contains(RequestStatus.WAITING);
    detail(inspection).andExpect(jsonPath("$.availableActions", not(hasItem("lock-alone"))));

    lockAlone(inspection, "solo")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));

    assertThat(inspectionRow(inspection)).containsEntry("status", "REQUESTED");
    assertThat(lockActions(inspection)).isZero();
  }

  @Test
  void 연결_전_상대는_혼자_잠글_수_없다() throws Exception {
    // 연결 전 상대에게는 요청을 열어 기다린다(03 1-4 "연결 전 상대" ①). 바로 혼자 진행으로 보지 않는다(SoloRule)
    seatWithoutMember(PartySide.LANDLORD, "INVITED", "01012345678");
    UUID inspection = requestedWith(1);

    lockAlone(inspection, "solo")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
  }

  @Test
  void 작성_중이거나_퇴거_점검이면_INVALID_STATE() throws Exception {
    seatWithoutMember(PartySide.LANDLORD, "EMPTY", null);
    UUID draft = created(start("MOVE_IN", "start-in"));

    lockAlone(draft, "solo-draft")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    UUID moveOut = created(start("MOVE_OUT", "start-out"));
    requestConfirm(moveOut, "request-out");
    lockAlone(moveOut, "solo-out")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(inspectionRow(moveOut)).containsEntry("status", "REQUESTED");
  }

  // ---- 상대 공유 중단(since = 그만둔 시각) ----

  @Test
  void 상대가_공유를_그만두면_혼자_잠그고_그만둔_뒤_상대_행동은_보이지_않아_막지_않는다() throws Exception {
    UUID landlord = member();
    connectedSeat(PartySide.LANDLORD, landlord);
    UUID inspection = requestedWith(2);
    Instant stoppedAt = clock.instant();
    clock.advance(Duration.ofHours(1));
    // 그만둔 시각보다 뒤의 상대 응답: since 뒤지만 보이는 범위(asOf) 밖이라 세지 않는다
    respond(landlord, items(inspection)[0], "a1").andExpect(status().isOk());
    stopSharingAndCloseRequests(inspection, stoppedAt);

    lockAlone(inspection, "solo").andExpect(status().isOk());

    assertThat(inspectionRow(inspection)).containsEntry("lock_reason", "SOLO");
    assertThat(lockPayload(inspection)).contains("\"SHARING_STOPPED\"");
  }

  @Test
  void 상대가_공유를_그만두기_전의_상대_행동은_막지_않는다() throws Exception {
    UUID landlord = member();
    connectedSeat(PartySide.LANDLORD, landlord);
    UUID inspection = requestedWith(2);
    respond(landlord, items(inspection)[0], "a1").andExpect(status().isOk());
    clock.advance(Duration.ofHours(1));
    stopSharingAndCloseRequests(inspection, clock.instant());

    lockAlone(inspection, "solo").andExpect(status().isOk());

    assertThat(lockPayload(inspection)).contains("\"SHARING_STOPPED\"");
  }

  @Test
  void 상대가_공유를_그만둬도_확인_요청이_기다리는_중이면_SOLO_NOT_ALLOWED() throws Exception {
    // 공유 중단의 요청 닫기(#370 A-6)가 빠진 상태: 되돌릴 수 없는 잠금보다 기다린다
    connectedSeat(PartySide.LANDLORD, member());
    UUID inspection = requestedWith(1);
    stopSharing(PartySide.LANDLORD, clock.instant());

    lockAlone(inspection, "solo")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
  }

  @Test
  void 내가_공유를_그만뒀으면_SHARING_STOPPED이고_당사자가_아니면_NOT_FOUND() throws Exception {
    seatWithoutMember(PartySide.LANDLORD, "EMPTY", null);
    UUID inspection = requestedWith(1);

    mvc.perform(
            post("/api/inspections/{id}/lock-alone", inspection)
                .with(loginAs(member()))
                .header("Idempotency-Key", "stranger"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    stopSharing(PartySide.TENANT, clock.instant());
    lockAlone(inspection, "solo").andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    assertThat(lockActions(inspection)).isZero();
  }

  private void stopSharingAndCloseRequests(UUID inspection, Instant at) {
    stopSharing(PartySide.LANDLORD, at);
    // 공유 중단 API의 closeAll(SHARING_STOPPED)(#370 A-6)이 할 일을 가짜로 대신한다
    waits.close(
        RequestTarget.of(TargetKind.INSPECTION, inspection),
        RequestKind.INSPECTION_CONFIRM,
        ClosedReason.SHARING_STOPPED);
  }

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }

  private ResultActions respond(UUID member, UUID item, String key) throws Exception {
    return mvc.perform(
        post("/api/inspection-items/{id}/respond", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"));
  }

  private UUID[] items(UUID inspection) {
    return jdbc.queryForList(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ? ORDER BY id",
            UUID.class,
            inspection)
        .toArray(UUID[]::new);
  }

  private void assertReplayedLock(UUID inspection) {
    List<ActionEntry> history =
        transactions.execute(ignored ->
            actions.findByRoom(room).stream()
                .filter(action -> ActionTarget.inspection(inspection).equals(action.target()))
                .filter(action -> InspectionTransitions.CHANGES.contains(action.type()))
                .toList());
    assertThat(history)
        .extracting(ActionEntry::type)
        .containsExactly(
            ActionType.START_INSPECTION, ActionType.REQUEST_INSPECTION_CONFIRM, ActionType.LOCK);
    assertThat(history.getLast().actorSide()).isEqualTo(PartySide.TENANT);
    InspectionState replayed = Replay.fold(inspectionRules, history);
    assertThat(replayed.status()).isEqualTo(InspectionStatus.LOCKED);
    assertThat(inspectionRow(inspection)).containsEntry("status", replayed.status().name());
  }

  private UUID requestedWith(int count) throws Exception {
    UUID inspection = created(start("MOVE_IN", "start"));
    for (int i = 0; i < count; i++) {
      created(
          mvc.perform(
              post("/api/inspections/{id}/items", inspection)
                  .with(loginAs(tenant))
                  .header("Idempotency-Key", "i" + i)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"roomPartId\":\""
                          + part
                          + "\",\"description\":\"벽지 찢어짐\",\"uploadTickets\":[]}")));
    }
    requestConfirm(inspection, "request");
    return inspection;
  }

  private ResultActions start(String kind, String key) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"" + kind + "\"}"));
  }

  private void requestConfirm(UUID inspection, String key) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", key))
        .andExpect(status().isOk());
  }

  private ResultActions lockAlone(UUID inspection, String key) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/lock-alone", inspection)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key));
  }

  private ResultActions detail(UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(tenant)));
  }

  private Map<String, Object> inspectionRow(UUID inspection) {
    return jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", inspection);
  }

  private long lockActions(UUID inspection) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'LOCK'",
        Long.class,
        inspection);
  }

  private String lockPayload(UUID inspection) {
    return jdbc.queryForObject(
        "SELECT payload::text FROM record.action WHERE inspection_id = ? AND type = 'LOCK'",
        String.class,
        inspection);
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

  private void connectedSeat(PartySide side, UUID memberId) {
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

  private void seatWithoutMember(PartySide side, String status, String phone) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        phone,
        status);
  }
}
