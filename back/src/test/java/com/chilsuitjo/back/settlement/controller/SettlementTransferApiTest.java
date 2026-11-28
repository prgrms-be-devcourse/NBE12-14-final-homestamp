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
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * 06 SET-4 정산 송금 기록·응답(4-8 {@code transfers}·{@code respond})을 보안·pipeline·진짜 행동 기록
 * 저장(PostgreSQL)까지 띄워 본다. 응답 요청은 가짜 기다림({@code FakeWaitService})이 받는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementTransferApiTest {

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
  }

  // ---- 기록(SET-4a·4b) ----

  @Test
  void SET_4a_반환을_기록하면_첫_판과_행동이_남고_세입자에게_수령_확인_요청이_간다() throws Exception {
    UUID id = created(transfer(landlord, "r1", "REFUND", 9_845_000, null));

    Map<String, Object> head =
        jdbc.queryForMap("SELECT * FROM state.settlement_transfer WHERE id = ?", id);
    assertThat(head)
        .containsEntry("direction", "REFUND")
        .containsEntry("recorded_by_side", "LANDLORD")
        .containsEntry("response", "PENDING");
    Map<String, Object> version =
        jdbc.queryForMap(
            "SELECT * FROM record.settlement_transfer_version WHERE settlement_transfer_id = ?",
            id);
    assertThat(version).containsEntry("no", 1).containsEntry("amount", 9_845_000L);
    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE settlement_transfer_id = ?", id);
    assertThat(action).containsEntry("type", "RECORD_TRANSFER");
    assertThat(action.get("content_digest")).isNotNull();
    assertThat(receipt(id, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    // 정산이 바뀌어 정산 모습 번호가 오른다(불변식 19)
    assertThat(snapshotNo()).isEqualTo(2);

    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].id").value(id.toString()))
        .andExpect(jsonPath("$.transfers[0].amount").value(9_845_000))
        .andExpect(jsonPath("$.transfers[0].versionNo").value(1))
        .andExpect(jsonPath("$.transfers[0].response").value("PENDING"))
        .andExpect(jsonPath("$.transfers[0].availableActions[0]").value("respond"))
        // 확인 전이라 합계에 들어가지 않는다
        .andExpect(jsonPath("$.confirmedRefund").value(0))
        .andExpect(jsonPath("$.unansweredForMe").value(1));
    detail(landlord).andExpect(jsonPath("$.transfers[0].availableActions[0]").value("corrections"));
  }

  @Test
  void 같은_키로_다시_보내면_처음_송금을_돌려준다() throws Exception {
    UUID first = created(transfer(landlord, "same", "REFUND", 100, null));

    transfer(landlord, "same", "REFUND", 100, null)
        .andExpect(status().isCreated())
        .andExpect(header().string(SettlementController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(count("state.settlement_transfer")).isEqualTo(1);
  }

  @Test
  void SET_4a_반환은_여러_번_기록한다() throws Exception {
    created(transfer(landlord, "r1", "REFUND", 5_000_000, null));
    created(transfer(landlord, "r2", "REFUND", 4_845_000, null));

    assertThat(count("state.settlement_transfer")).isEqualTo(2);
    detail(tenant).andExpect(jsonPath("$.transfers.length()").value(2));
  }

  @Test
  void SET_4b_받은_쪽은_혼자_받았다고_남기고_보낸_쪽이_맞아요를_누르면_합계에_들어간다() throws Exception {
    UUID id = created(transfer(tenant, "recv", "REFUND", 9_845_000, null));

    assertThat(receipt(id, PartySide.TENANT)).contains(RequestStatus.WAITING);
    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].recordedBySide").value("TENANT"))
        .andExpect(jsonPath("$.confirmedRefund").value(0));

    respond(landlord, id, "ack", "ACK", null, 1).andExpect(status().isOk());

    assertThat(receipt(id, PartySide.TENANT)).contains(RequestStatus.ANSWERED);
    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].response").value("ACK"))
        .andExpect(jsonPath("$.transfers[0].confirmedAmount").value(9_845_000))
        .andExpect(jsonPath("$.confirmedRefund").value(9_845_000));
  }

  @Test
  void 추가_송금은_세입자가_보내고_집주인이_받는다() throws Exception {
    UUID id = created(transfer(tenant, "extra", "EXTRA", 30_000, null));

    respond(landlord, id, "ack", "ACK", null, 1).andExpect(status().isOk());
    detail(landlord)
        .andExpect(jsonPath("$.confirmedExtra").value(30_000))
        .andExpect(jsonPath("$.confirmedRefund").value(0));
  }

  @Test
  void 금액이_없거나_0이하거나_날짜가_없으면_VALIDATION_FAILED() throws Exception {
    transfer(landlord, "zero", "REFUND", 0, null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    postTransfer(landlord, "no-date", "{\"direction\":\"REFUND\",\"amount\":100}")
        .andExpect(status().isBadRequest());
    postTransfer(landlord, "no-dir", "{\"amount\":100,\"paidOn\":\"2026-11-28\"}")
        .andExpect(status().isBadRequest());
    assertThat(count("state.settlement_transfer")).isZero();
  }

  @Test
  void 연결_전_상대면_요청_없이_혼자_기록한다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'INVITED', member_id = NULL"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    UUID id = created(transfer(landlord, "solo", "REFUND", 100, null));

    assertThat(receipt(id, PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void 없는_정산이나_당사자가_아니면_NOT_FOUND() throws Exception {
    mvc.perform(
            post("/api/settlements/{id}/transfers", UUID.randomUUID())
                .with(loginAs(landlord))
                .header("Idempotency-Key", "x")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("REFUND", 100, null)))
        .andExpect(status().isNotFound());
    transfer(member(), "stranger", "REFUND", 100, null).andExpect(status().isNotFound());
  }

  @Test
  void 정산_완료_뒤에는_SETTLED() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    transfer(landlord, "settled", "REFUND", 100, null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLED"));
  }

  // ---- 응답 ----

  @Test
  void 기록한_측이_답하면_NOT_COUNTERPART_SIDE() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 100, null));

    respond(landlord, id, "own", "ACK", null, 1)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void 본_판이_다르면_VERSION_CONFLICT() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 100, null));

    respond(tenant, id, "old", "ACK", null, 2)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void 금액이_달라요는_사유가_필요하고_맞아요는_되돌릴_수_없다() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 100, null));

    respond(tenant, id, "no-reason", "DISPUTE", " ", 1)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    respond(tenant, id, "ack", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACK"));
    respond(tenant, id, "again", "DISPUTE", "다름", 1)
        .andExpect(jsonPath("$.code").value("ACK_FINAL"));
  }

  @Test
  void 같은_키로_다시_응답하면_처음_결과다() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 100, null));

    respond(tenant, id, "same", "ACK", null, 1).andExpect(status().isOk());
    respond(tenant, id, "same", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().string(SettlementController.REPLAYED, "true"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE settlement_transfer_id = ?"
                    + " AND type = 'RESPOND_TRANSFER'",
                Long.class,
                id))
        .isEqualTo(1);
  }

  // ---- 같은 송금(SET-4e) ----

  @Test
  void SET_4e_받은_금액이_다르면_이견을_남기고_받은_금액을_묶어_적고_한_번만_센다() throws Exception {
    UUID sent = created(transfer(landlord, "sent", "REFUND", 9_845_000, null));
    respond(tenant, sent, "dispute", "DISPUTE", "통장에 9,800,000만 들어왔어요", 1)
        .andExpect(status().isOk());

    UUID received = created(transfer(tenant, "recv", "REFUND", 9_800_000, sent));
    respond(landlord, received, "ack", "ACK", null, 1).andExpect(status().isOk());

    detail(landlord)
        .andExpect(jsonPath("$.transfers[0].response").value("DISPUTE"))
        .andExpect(jsonPath("$.transfers[1].sameAsTransferId").value(sent.toString()))
        .andExpect(jsonPath("$.confirmedRefund").value(9_800_000));
  }

  @Test
  void 둘_다_확인돼도_같은_송금은_받은_쪽_금액으로_한_번만_센다() throws Exception {
    UUID sent = created(transfer(landlord, "sent", "REFUND", 9_845_000, null));
    UUID received = created(transfer(tenant, "recv", "REFUND", 9_800_000, sent));
    respond(tenant, sent, "ack-sent", "ACK", null, 1).andExpect(status().isOk());
    respond(landlord, received, "ack-recv", "ACK", null, 1).andExpect(status().isOk());

    detail(tenant).andExpect(jsonPath("$.confirmedRefund").value(9_800_000));
  }

  @Test
  void 보낸_쪽이_묶거나_다른_방향의_기록에_묶으면_VALIDATION_FAILED() throws Exception {
    UUID sent = created(transfer(landlord, "sent", "REFUND", 100, null));

    transfer(landlord, "self-link", "REFUND", 100, sent)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    transfer(landlord, "extra-recv", "EXTRA", 100, sent)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    transfer(tenant, "unknown", "REFUND", 100, UUID.randomUUID())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(count("state.settlement_transfer")).isEqualTo(1);
  }

  // ---- 고치기(SET-4c·4d) ----

  @Test
  void SET_4c_수령_확인_전이면_그냥_고쳐지고_확인_요청은_바뀐_판으로_이어진다() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 9_485_000, null));

    correct(landlord, id, "fix", 9_845_000, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("APPLIED"));

    assertThat(snapshotNo()).isEqualTo(3);
    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].amount").value(9_845_000))
        .andExpect(jsonPath("$.transfers[0].versionNo").value(2));
    assertThat(receipt(id, PartySide.LANDLORD)).contains(RequestStatus.WAITING);
    respond(tenant, id, "old-ack", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    respond(tenant, id, "ack", "ACK", null, 2).andExpect(status().isOk());
    detail(tenant).andExpect(jsonPath("$.confirmedRefund").value(9_845_000));
  }

  @Test
  void SET_4d_수령_확인_뒤에는_상대가_확인해야_고쳐지고_거절하면_합계는_그대로다() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 9_845_000, null));
    respond(tenant, id, "ack", "ACK", null, 1).andExpect(status().isOk());

    String body =
        correct(landlord, id, "fix", 9_800_000, "숫자를 잘못 적었어요")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("REQUESTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID correction =
        UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));

    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].amount").value(9_845_000))
        .andExpect(jsonPath("$.transfers[0].corrections[0].newValue").value(9_800_000))
        .andExpect(jsonPath("$.confirmedRefund").value(9_845_000));

    mvc.perform(
            post("/api/corrections/{id}/respond", correction)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"DISPUTE\",\"reason\":\"받은 건 맞아요\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());
    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].amount").value(9_845_000))
        .andExpect(jsonPath("$.confirmedRefund").value(9_845_000));
  }

  @Test
  void SET_4d_상대가_고친_내용을_확인하면_합계가_바뀐다() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 9_845_000, null));
    respond(tenant, id, "ack", "ACK", null, 1).andExpect(status().isOk());
    String body =
        correct(landlord, id, "fix", 9_800_000, "숫자를 잘못 적었어요")
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID correction =
        UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));

    mvc.perform(
            post("/api/corrections/{id}/respond", correction)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());

    detail(tenant)
        .andExpect(jsonPath("$.transfers[0].amount").value(9_800_000))
        .andExpect(jsonPath("$.transfers[0].confirmedAmount").value(9_800_000))
        .andExpect(jsonPath("$.confirmedRefund").value(9_800_000));
  }

  @Test
  void 송금_고치기에_없는_칸이나_틀린_값이면_VALIDATION_FAILED() throws Exception {
    UUID id = created(transfer(landlord, "r", "REFUND", 100, null));

    correct(landlord, id, "zero", 0, null).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/corrections")
                .with(loginAs(landlord))
                .header("Idempotency-Key", "field")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetKind\":\"SETTLEMENT_TRANSFER\",\"targetId\":\""
                        + id
                        + "\",\"changes\":[{\"field\":\"direction\",\"newValue\":\"EXTRA\"}]}"))
        .andExpect(status().isBadRequest());
  }

  // ---- 공유 중단(불변식 18) ----

  @Test
  void 공유를_그만둔_뒤_상대가_남긴_송금은_그만둔_사람에게_보이지_않고_요청도_열지_않는다() throws Exception {
    UUID before = created(transfer(landlord, "before", "REFUND", 100, null));
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        Timestamp.from(Instant.now()),
        room);

    UUID after = created(transfer(landlord, "after", "REFUND", 200, null));

    assertThat(receipt(after, PartySide.LANDLORD)).isEmpty();
    detail(tenant)
        .andExpect(jsonPath("$.transfers.length()").value(1))
        .andExpect(jsonPath("$.transfers[0].id").value(before.toString()))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    detail(landlord).andExpect(jsonPath("$.transfers.length()").value(2));
  }

  // ---- 도우미 ----

  private Optional<RequestStatus> receipt(UUID transfer, PartySide requestedBy) {
    return waits.statusOf(
        RequestTarget.of(TargetKind.SETTLEMENT_TRANSFER, transfer),
        RequestKind.RECEIPT_CONFIRM,
        requestedBy);
  }

  private ResultActions transfer(
      UUID member, String key, String direction, long amount, UUID sameAs) throws Exception {
    return postTransfer(member, key, body(direction, amount, sameAs));
  }

  private static String body(String direction, long amount, UUID sameAs) {
    return "{\"direction\":\""
        + direction
        + "\",\"amount\":"
        + amount
        + ",\"paidOn\":\"2026-11-28\""
        + (sameAs == null ? "" : ",\"sameAsTransferId\":\"" + sameAs + "\"")
        + "}";
  }

  private ResultActions postTransfer(UUID member, String key, String json) throws Exception {
    return mvc.perform(
        post("/api/settlements/{id}/transfers", settlement)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions respond(
      UUID member, UUID transfer, String key, String answer, String reason, int seen)
      throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\",\"seenVersionNo\":"
            + seen
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return mvc.perform(
        post("/api/settlement-transfers/{id}/respond", transfer)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions correct(UUID member, UUID transfer, String key, long amount, String reason)
      throws Exception {
    String json =
        "{\"targetKind\":\"SETTLEMENT_TRANSFER\",\"targetId\":\""
            + transfer
            + "\",\"changes\":[{\"field\":\"amount\",\"newValue\":"
            + amount
            + "}]"
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return mvc.perform(
        post("/api/corrections")
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions detail(UUID member) throws Exception {
    return mvc.perform(get("/api/settlements/{id}", settlement).with(loginAs(member)));
  }

  private int snapshotNo() {
    return jdbc.queryForObject(
        "SELECT snapshot_no FROM state.settlement WHERE id = ?", Integer.class, settlement);
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

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }
}
