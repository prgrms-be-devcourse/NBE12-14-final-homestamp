package com.chilsuitjo.back.settlement.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * 06 SET-5 정산 끝내기(4-8 {@code finish}·{@code confirm}·{@code not-yet}·{@code alone})를 보안·pipeline·진짜
 * 행동 기록 저장(PostgreSQL)까지 띄워 본다. 응답 요청은 가짜 기다림({@code FakeWaitService})이 받는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementFinishApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID settlement;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room();
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    settlement =
        created(
            mvc.perform(
                post("/api/rooms/{id}/settlement", room)
                    .with(loginAs(landlord))
                    .header("Idempotency-Key", "open")));
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
  }

  // ---- SET-5a ----

  @Test
  void SET_5a_끝내기를_제안하고_상대가_확인하면_정산_완료와_삭제_예정일이_정해진다() throws Exception {
    finish(tenant, "propose").andExpect(status().isOk());

    assertThat(finishRequest().status()).isEqualTo(RequestStatus.WAITING);
    assertThat(finishRequest().requestedBySide()).isEqualTo(PartySide.TENANT);
    detail(tenant)
        .andExpect(jsonPath("$.finishRequestedBy").value("TENANT"))
        .andExpect(jsonPath("$.availableActions", contains("transfers")));
    detail(landlord)
        .andExpect(
            jsonPath(
                "$.availableActions",
                containsInAnyOrder("transfers", "finish-confirm", "finish-not-yet")));

    confirm(landlord, "confirm", snapshotNo())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settled").value(true));

    Map<String, Object> roomRow =
        jdbc.queryForMap("SELECT * FROM state.room_record WHERE id = ?", room);
    assertThat(roomRow).containsEntry("status", "SETTLED");
    Instant settledAt = ((Timestamp) roomRow.get("settled_at")).toInstant();
    assertThat(((Timestamp) roomRow.get("delete_due_at")).toInstant())
        .isEqualTo(settledAt.plus(Duration.ofDays(365)));
    assertThat(finishRequest().status()).isEqualTo(RequestStatus.ANSWERED);
    detail(tenant)
        .andExpect(jsonPath("$.settled").value(true))
        .andExpect(jsonPath("$.availableActions").isEmpty());
  }

  @Test
  void 같은_키로_다시_확인하면_처음_결과다() throws Exception {
    finish(tenant, "propose");
    int seen = snapshotNo();

    confirm(landlord, "same", seen).andExpect(status().isOk());
    confirm(landlord, "same", seen)
        .andExpect(status().isOk())
        .andExpect(header().string(SettlementController.REPLAYED, "true"))
        .andExpect(jsonPath("$.settled").value(true));
  }

  @Test
  void 거주_중에는_끝내기를_제안할_수_없다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'LIVING', ended_at = NULL WHERE id = ?", room);

    finish(tenant, "living")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 요청한_측은_확인할_수_없고_열린_요청이_없으면_INVALID_STATE() throws Exception {
    confirm(landlord, "none", snapshotNo()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    finish(tenant, "propose");

    confirm(tenant, "own", snapshotNo())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    finish(landlord, "both")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // ---- SET-5b·5e ----

  @Test
  void SET_5b_5e_기다리는_동안_정산이_바뀌면_요청이_닫히고_본_모습으로_확인하면_VERSION_CONFLICT() throws Exception {
    finish(tenant, "propose");
    int seen = snapshotNo();

    transfer(landlord, "refund", 9_845_000);

    RequestView closed = finishRequest();
    assertThat(closed.status()).isEqualTo(RequestStatus.CLOSED);
    assertThat(closed.closedReason()).isEqualTo(ClosedReason.SETTLEMENT_CHANGED);
    assertThat(actions("CLOSE_FINISH_REQUEST")).isEqualTo(1);
    confirm(landlord, "stale", seen)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    assertThat(roomStatus()).isEqualTo("ENDED");
    // 정산이 바뀌어 닫힌 요청은 혼자 끝내기를 열지 않고, 7일 제한 없이 다시 보낸다
    alone(tenant, "alone").andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
    finish(tenant, "again").andExpect(status().isOk());
  }

  @Test
  void SET_5b_기다리는_동안_확인_뒤_송금을_고쳐_요청만_해도_끝내기_요청이_닫힌다() throws Exception {
    UUID transfer = transfer(landlord, "refund", 9_845_000);
    respond(tenant, transfer, "ack");
    finish(tenant, "propose");
    int seen = snapshotNo();

    mvc.perform(
            post("/api/corrections")
                .with(loginAs(landlord))
                .header("Idempotency-Key", "fix")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetKind\":\"SETTLEMENT_TRANSFER\",\"targetId\":\""
                        + transfer
                        + "\",\"changes\":[{\"field\":\"amount\",\"newValue\":9800000}],"
                        + "\"reason\":\"잘못 적었어요\"}"))
        .andExpect(jsonPath("$.result").value("REQUESTED"));

    assertThat(finishRequest().closedReason()).isEqualTo(ClosedReason.SETTLEMENT_CHANGED);
    assertThat(actions("CLOSE_FINISH_REQUEST")).isEqualTo(1);
    confirm(landlord, "stale", seen).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  // ---- SET-5c ----

  @Test
  void SET_5c_아직_안_끝났어요는_사유가_필요하고_답한_뒤_7일_안에는_다시_요청할_수_없고_혼자_끝낼_수_없다() throws Exception {
    finish(tenant, "propose");

    notYet(landlord, "blank", " ").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    notYet(landlord, "not-yet", "관리비 정산이 남았어요").andExpect(status().isOk());

    assertThat(finishRequest().status()).isEqualTo(RequestStatus.ANSWERED);
    finish(tenant, "again")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    alone(tenant, "alone").andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
    // 7일 제한은 [아직 안 끝났어요]를 받은 쪽에만 걸린다
    finish(landlord, "other-side").andExpect(status().isOk());
  }

  // ---- SET-5d ----

  @Test
  void SET_5d_답이_없어_미확인이_되면_제안한_쪽이_혼자_끝낸다() throws Exception {
    finish(tenant, "propose");
    alone(tenant, "early").andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));

    waits.markUnconfirmed(target(), RequestKind.SETTLE_FINISH);
    detail(tenant).andExpect(jsonPath("$.availableActions", contains("transfers", "finish-alone")));
    alone(tenant, "alone").andExpect(status().isOk()).andExpect(jsonPath("$.settled").value(true));

    assertThat(roomStatus()).isEqualTo("SETTLED");
    Map<String, Object> action =
        jdbc.queryForMap(
            "SELECT * FROM record.action WHERE room_record_id = ? AND type = 'FINISH_ALONE'", room);
    assertThat(action.get("payload").toString()).contains("UNCONFIRMED");
  }

  @Test
  void 상대가_없으면_요청_없이_바로_혼자_끝낸다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'EMPTY', member_id = NULL, connected_at = NULL"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    finish(landlord, "propose").andExpect(jsonPath("$.code").value("INVALID_STATE"));
    alone(landlord, "alone").andExpect(jsonPath("$.settled").value(true));
  }

  // ---- 공유 중단(CONS-3g) ----

  @Test
  void 상대가_공유를_그만둔_동안은_끝내기를_보내거나_혼자_끝낼_수_없다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        Timestamp.from(Instant.now()),
        room);

    finish(tenant, "propose").andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    alone(tenant, "alone").andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    detail(tenant).andExpect(jsonPath("$.availableActions", contains("transfers")));
  }

  // ---- SET-5f ----

  @Test
  void SET_5f_정산이_완료되면_남은_기다림과_확인_전_고친_내용이_모두_닫힌다() throws Exception {
    UUID transfer = transfer(landlord, "refund", 9_845_000);
    respond(tenant, transfer, "ack");
    String body =
        mvc.perform(
                post("/api/corrections")
                    .with(loginAs(landlord))
                    .header("Idempotency-Key", "fix")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"targetKind\":\"SETTLEMENT_TRANSFER\",\"targetId\":\""
                            + transfer
                            + "\",\"changes\":[{\"field\":\"amount\",\"newValue\":9800000}],"
                            + "\"reason\":\"잘못 적었어요\"}"))
            .andExpect(jsonPath("$.result").value("REQUESTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID correction =
        UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));
    UUID otherTransfer = transfer(landlord, "refund-2", 100);
    RequestTarget defect = RequestTarget.of(TargetKind.REPAIR_CASE, UUID.randomUUID());
    waits.open(new OpenRequestCommand(room, RequestKind.DEFECT_CONFIRM, defect, PartySide.TENANT));

    finish(tenant, "propose");
    confirm(landlord, "confirm", snapshotNo()).andExpect(jsonPath("$.settled").value(true));

    assertThat(
            jdbc.queryForMap(
                "SELECT status, closed_reason FROM state.correction_request WHERE id = ?",
                correction))
        .containsEntry("status", "CLOSED")
        .containsEntry("closed_reason", "SETTLED");
    assertThat(
            waits.statusOf(
                RequestTarget.of(TargetKind.SETTLEMENT_TRANSFER, otherTransfer),
                RequestKind.RECEIPT_CONFIRM,
                PartySide.LANDLORD))
        .contains(RequestStatus.CLOSED);
    assertThat(waits.statusOf(defect, RequestKind.DEFECT_CONFIRM)).contains(RequestStatus.CLOSED);
    // 닫힌 것에 응답하면 SETTLED로 거절된다
    mvc.perform(
            post("/api/settlement-transfers/{id}/respond", otherTransfer)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "late")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(jsonPath("$.code").value("SETTLED"));
  }

  // ---- 도우미 ----

  private RequestTarget target() {
    return RequestTarget.of(TargetKind.SETTLEMENT, settlement);
  }

  private RequestView finishRequest() {
    return waits.latest(RequestKind.SETTLE_FINISH, target()).orElseThrow();
  }

  private ResultActions finish(UUID member, String key) throws Exception {
    return mvc.perform(
        post("/api/settlements/{id}/finish", settlement)
            .with(loginAs(member))
            .header("Idempotency-Key", key));
  }

  private ResultActions confirm(UUID member, String key, int seen) throws Exception {
    return mvc.perform(
        post("/api/settlements/{id}/finish/confirm", settlement)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"seenVersionNo\":" + seen + "}"));
  }

  private ResultActions notYet(UUID member, String key, String reason) throws Exception {
    return mvc.perform(
        post("/api/settlements/{id}/finish/not-yet", settlement)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"));
  }

  private ResultActions alone(UUID member, String key) throws Exception {
    return mvc.perform(
        post("/api/settlements/{id}/finish/alone", settlement)
            .with(loginAs(member))
            .header("Idempotency-Key", key));
  }

  private UUID transfer(UUID member, String key, long amount) throws Exception {
    return created(
        mvc.perform(
            post("/api/settlements/{id}/transfers", settlement)
                .with(loginAs(member))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"direction\":\"REFUND\",\"amount\":"
                        + amount
                        + ",\"paidOn\":\"2026-11-28\"}")));
  }

  private void respond(UUID member, UUID transfer, String key) throws Exception {
    mvc.perform(
            post("/api/settlement-transfers/{id}/respond", transfer)
                .with(loginAs(member))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());
  }

  private ResultActions detail(UUID member) throws Exception {
    return mvc.perform(get("/api/settlements/{id}", settlement).with(loginAs(member)));
  }

  private int snapshotNo() {
    return jdbc.queryForObject(
        "SELECT snapshot_no FROM state.settlement WHERE id = ?", Integer.class, settlement);
  }

  private String roomStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM state.room_record WHERE id = ?", String.class, room);
  }

  private long actions(String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE room_record_id = ? AND type = ?",
        Long.class,
        room,
        type);
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
}
