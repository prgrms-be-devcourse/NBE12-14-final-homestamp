package com.chilsuitjo.back.settlement.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * 06 4-8 정산 열기·조회(SET-1)와 퇴거 점검 시작 때 정산 열기(03 H·I, #320)를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워
 * 본다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;

  private UUID room;
  private UUID tenant;
  private UUID landlord;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room();
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant, "CONNECTED");
    seat(PartySide.LANDLORD, landlord, "CONNECTED");
  }

  // ---- 정산 열기(SET-1) ----

  @Test
  void 열면_정산과_행동이_함께_생기고_보증금_확인_전_정산_모습_1번이다() throws Exception {
    UUID id = created(open(tenant, "k1"));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM state.settlement WHERE id = ?", id);
    assertThat(row)
        .containsEntry("room_record_id", room)
        .containsEntry("deposit_status", "NOT_ENTERED")
        .containsEntry("snapshot_no", 1);
    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE settlement_id = ?", id);
    assertThat(action)
        .containsEntry("type", "OPEN_SETTLEMENT")
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT");
    assertThat(row.get("created_at")).isEqualTo(action.get("created_at"));

    detail(landlord, id)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()))
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.depositStatus").value("NOT_ENTERED"))
        .andExpect(jsonPath("$.snapshotNo").value(1))
        .andExpect(jsonPath("$.unansweredForMe").value(0))
        .andExpect(jsonPath("$.availableActions[0]").value("transfers"));
  }

  @Test
  void 같은_키로_다시_보내면_처음_정산을_돌려준다() throws Exception {
    UUID first = created(open(tenant, "same"));

    open(tenant, "same")
        .andExpect(status().isCreated())
        .andExpect(header().string(SettlementController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(count("state.settlement")).isEqualTo(1);
  }

  @Test
  void 이미_열렸으면_SETTLEMENT_EXISTS이고_호실에서_그_정산을_찾는다() throws Exception {
    UUID first = created(open(landlord, "a"));

    open(tenant, "b")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLEMENT_EXISTS"));
    ofRoom(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(first.toString()))
        .andExpect(jsonPath("$.mySide").value("TENANT"));
    assertThat(count("state.settlement")).isEqualTo(1);
  }

  @Test
  void 동시에_열면_하나만_열리고_다른_쪽은_SETTLEMENT_EXISTS() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (UUID member : List.of(tenant, landlord)) {
        Callable<Integer> call =
            () -> {
              go.await();
              return open(member, "race-" + member).andReturn().getResponse().getStatus();
            };
        results.add(pool.submit(call));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("state.settlement")).isEqualTo(1);
    assertThat(actions("OPEN_SETTLEMENT")).isEqualTo(1);
  }

  @Test
  void 거주_종료_뒤에도_연다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    created(open(tenant, "after-end"));
  }

  @Test
  void 정산_완료_뒤에는_SETTLED() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    open(tenant, "settled")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLED"));
    assertThat(count("state.settlement")).isZero();
  }

  @Test
  void 공유를_그만둔_사람은_SHARING_STOPPED() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        Timestamp.from(Instant.now()),
        room);

    open(tenant, "stopped")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    assertThat(count("state.settlement")).isZero();
  }

  @Test
  void 당사자가_아니면_열기와_조회_모두_NOT_FOUND() throws Exception {
    UUID stranger = member();
    open(stranger, "x")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    UUID id = created(open(tenant, "mine"));

    detail(stranger, id).andExpect(status().isNotFound());
    ofRoom(stranger).andExpect(status().isNotFound());
  }

  @Test
  void 없는_정산과_아직_열지_않은_호실은_NOT_FOUND() throws Exception {
    detail(tenant, UUID.randomUUID()).andExpect(status().isNotFound());
    ofRoom(tenant)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- 아직 답하지 않은 확인 수(SET-5g) ----

  @Test
  void SET_5g_내가_답할_쪽인_열린_요청만_센다() throws Exception {
    UUID id = created(open(tenant, "count"));
    // 집주인이 연 요청 둘(세입자가 답할 쪽)과 세입자가 연 요청 하나
    waits.open(request(RequestKind.DEFECT_CONFIRM, TargetKind.REPAIR_CASE, PartySide.LANDLORD));
    waits.open(request(RequestKind.DEFECT_CONFIRM, TargetKind.REPAIR_CASE, PartySide.LANDLORD));
    waits.open(request(RequestKind.DEFECT_CONFIRM, TargetKind.REPAIR_CASE, PartySide.TENANT));

    detail(tenant, id).andExpect(jsonPath("$.unansweredForMe").value(2));
    detail(landlord, id).andExpect(jsonPath("$.unansweredForMe").value(1));
  }

  // ---- 퇴거 점검 시작 때 정산 열기(#320) ----

  @Test
  void 퇴거_점검을_시작하면_같은_요청에서_정산이_열린다() throws Exception {
    UUID inspection = created(startInspection(landlord, "out", "MOVE_OUT"));

    Map<String, Object> settlement =
        jdbc.queryForMap("SELECT * FROM state.settlement WHERE room_record_id = ?", room);
    assertThat(settlement).containsEntry("deposit_status", "NOT_ENTERED");
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT type, request_id, inspection_id, settlement_id FROM record.action"
                + " WHERE room_record_id = ? ORDER BY room_seq",
            room);
    assertThat(rows)
        .extracting(r -> r.get("type"))
        .containsExactly("START_INSPECTION", "OPEN_SETTLEMENT");
    assertThat(rows.get(0).get("inspection_id")).isEqualTo(inspection);
    assertThat(rows.get(1).get("settlement_id")).isEqualTo(settlement.get("id"));
    assertThat(rows.get(1).get("request_id")).isEqualTo(rows.get(0).get("request_id"));
  }

  @Test
  void 정산이_이미_있으면_퇴거_점검_시작은_정산을_다시_열지_않는다() throws Exception {
    UUID settlement = created(open(tenant, "first"));

    created(startInspection(landlord, "out", "MOVE_OUT"));

    assertThat(count("state.settlement")).isEqualTo(1);
    assertThat(actions("OPEN_SETTLEMENT")).isEqualTo(1);
    ofRoom(landlord).andExpect(jsonPath("$.id").value(settlement.toString()));
  }

  @Test
  void 퇴거_점검_시작을_같은_키로_다시_보내면_처음_점검을_돌려주고_정산은_하나다() throws Exception {
    UUID first = created(startInspection(tenant, "same-out", "MOVE_OUT"));

    startInspection(tenant, "same-out", "MOVE_OUT")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(count("state.settlement")).isEqualTo(1);
    assertThat(actions("OPEN_SETTLEMENT")).isEqualTo(1);
  }

  @Test
  void 퇴거_점검_시작이_거절되면_정산도_열리지_않는다() throws Exception {
    jdbc.update(
        """
        INSERT INTO state.inspection
          (id, room_record_id, kind, author_side, status, late, created_at, version)
        VALUES (?, ?, 'MOVE_OUT', 'TENANT', 'DRAFT', false, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room);

    startInspection(landlord, "dup", "MOVE_OUT")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSPECTION_EXISTS"));
    assertThat(count("state.settlement")).isZero();
  }

  @Test
  void 입주_점검_시작은_정산을_열지_않는다() throws Exception {
    created(startInspection(tenant, "in", "MOVE_IN"));

    assertThat(count("state.settlement")).isZero();
  }

  // ---- 도우미 ----

  private OpenRequestCommand request(RequestKind kind, TargetKind target, PartySide by) {
    return new OpenRequestCommand(room, kind, RequestTarget.of(target, UUID.randomUUID()), by);
  }

  private ResultActions open(UUID member, String key) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/settlement", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key));
  }

  private ResultActions detail(UUID member, UUID settlement) throws Exception {
    return mvc.perform(get("/api/settlements/{id}", settlement).with(loginAs(member)));
  }

  private ResultActions ofRoom(UUID member) throws Exception {
    return mvc.perform(get("/api/rooms/{id}/settlement", room).with(loginAs(member)));
  }

  private ResultActions startInspection(UUID member, String key, String kind) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"" + kind + "\"}"));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', ?, 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        Date.valueOf(LocalDate.parse("2026-03-01")));
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private void seat(PartySide side, UUID memberId, String status) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        memberId,
        status);
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }

  private long actions(String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE room_record_id = ? AND type = ?",
        Long.class,
        room,
        type);
  }
}
