package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
import com.chilsuitjo.back.notification.service.FakeWaitService;
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
 * 06 REP-5c·5e·5f 혼자 종료와 혼자 끝낸 건의 늦은 종료 확인·이견을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#237).
 * 혼자 진행 판단은 진짜 {@code SoloRule}이 실제 자리 상태를 읽는다({@code FakeWaitService.canGoSoloDirectly}는 가짜가 아님).
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairCloseAloneApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    part = part();
    connected(PartySide.TENANT, tenant);
  }

  // ---- REP-5e: 물을 상대가 없으면 혼자 종료 ----

  @Test
  void REP_5e_상대가_없으면_종료_요청_없이_혼자_종료해_처리_완료_내_확인이_된다() throws Exception {
    emptySeat(PartySide.LANDLORD);
    UUID caseId = started();
    detail(caseId, tenant).andExpect(jsonPath("$.availableActions", hasItem("close-alone")));

    act(caseId, tenant, "close-alone", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closedBy").value("TENANT"));

    assertThat(caseRow(caseId))
        .containsEntry("status", "DONE")
        .containsEntry("closed_by", "TENANT");
    assertThat(soloReason(caseId)).isEqualTo("NO_COUNTERPART");
    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.CLOSE_CONFIRM)).isEmpty();
    detail(caseId, tenant)
        .andExpect(jsonPath("$.closedBy").value("TENANT"))
        .andExpect(jsonPath("$.closeObjection").value(nullValue()))
        .andExpect(jsonPath("$.availableActions", not(hasItem("close-alone"))));
  }

  @Test
  void 번호만_있는_자리도_상대가_없는_것이라_혼자_종료한다() throws Exception {
    numberOnlySeat(PartySide.LANDLORD);
    UUID caseId = started();

    act(caseId, tenant, "close-alone", null).andExpect(status().isOk());
    assertThat(soloReason(caseId)).isEqualTo("NO_COUNTERPART");
  }

  @Test
  void 같은_키로_다시_보내면_첫_결과를_돌려주고_행동을_더_남기지_않는다() throws Exception {
    // 재생은 측을 모르는 채 load를 지나므로(ClosingAlone.load의 side == null) 혼자 진행 판단을 부르지 않는다
    emptySeat(PartySide.LANDLORD);
    UUID caseId = started();
    String key = "close-alone-" + UUID.randomUUID();

    closeAlone(caseId, key).andExpect(status().isOk());
    closeAlone(caseId, key)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closedBy").value("TENANT"));

    assertThat(countActions(caseId, "CLOSE_ALONE")).isEqualTo(1);
  }

  @Test
  void 보류가_열린_채_혼자_종료하면_강행_표시를_남긴다() throws Exception {
    // 03 G "강행 표시", 05 2-3 payload while_open_objection. 상대가 보류를 남긴 뒤 자리가 빈 모양(KEEP-4 뒤와 같음)
    connected(PartySide.LANDLORD, landlord);
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요");
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'EMPTY', member_id = NULL, connected_at = NULL"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    act(caseId, tenant, "close-alone", null).andExpect(status().isOk());

    assertThat(objectionMark(caseId, "CLOSE_ALONE")).isEqualTo("NEED_MORE");
    assertThat(soloReason(caseId)).isEqualTo("NO_COUNTERPART");
  }

  // ---- REP-5c: 물을 상대가 있으면 혼자 종료하지 않는다 ----

  @Test
  void REP_5c_상대가_연결돼_있으면_혼자_종료는_SOLO_NOT_ALLOWED이고_버튼도_없다() throws Exception {
    connected(PartySide.LANDLORD, landlord);
    UUID caseId = started();
    detail(caseId, tenant).andExpect(jsonPath("$.availableActions", not(hasItem("close-alone"))));

    act(caseId, tenant, "close-alone", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
    assertThat(caseRow(caseId)).containsEntry("status", "IN_PROGRESS");
  }

  @Test
  void REP_5c_종료_요청이_미확인이_돼도_혼자_종료는_SOLO_NOT_ALLOWED() throws Exception {
    connected(PartySide.LANDLORD, landlord);
    UUID caseId = started();
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());
    waits.markUnconfirmed(caseRequest(caseId), RequestKind.CLOSE_CONFIRM);

    act(caseId, tenant, "close-alone", null)
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
    assertThat(caseRow(caseId)).containsEntry("status", "CLOSE_WAIT");
  }

  @Test
  void 연결_전_상대나_공유를_그만둔_상대도_물을_상대라_혼자_종료하지_않는다() throws Exception {
    invitedSeat(PartySide.LANDLORD);
    UUID invitedCase = started();
    act(invitedCase, tenant, "close-alone", null)
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));

    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', member_id = ?,"
            + " connected_at = CURRENT_TIMESTAMP, sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        landlord,
        room);
    act(invitedCase, tenant, "close-alone", null)
        .andExpect(jsonPath("$.code").value("SOLO_NOT_ALLOWED"));
  }

  @Test
  void 시작_전이면_혼자_종료는_INVALID_STATE() throws Exception {
    emptySeat(PartySide.LANDLORD);
    UUID caseId = register();

    act(caseId, tenant, "close-alone", null).andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 거주가_끝나면_혼자_종료는_ROOM_ENDED() throws Exception {
    emptySeat(PartySide.LANDLORD);
    UUID caseId = started();
    endRoom();

    act(caseId, tenant, "close-alone", null).andExpect(jsonPath("$.code").value("ROOM_ENDED"));
    detail(caseId, tenant).andExpect(jsonPath("$.availableActions", not(hasItem("close-alone"))));
  }

  // ---- REP-5f: 혼자 끝낸 건에 상대가 늦게 확인하거나 이견을 남긴다 ----

  @Test
  void REP_5f_나중에_연결한_상대의_아직_안_됐어요는_상태를_바꾸지_않고_이견으로_보인다() throws Exception {
    UUID caseId = closedAloneThenLandlordJoins();

    act(caseId, landlord, "not-yet", "{\"reason\":\"아직 물이 새요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closedBy").value("TENANT"));

    assertThat(caseRow(caseId))
        .containsEntry("status", "DONE")
        .containsEntry("closed_by", "TENANT");
    detail(caseId, tenant)
        .andExpect(jsonPath("$.closeObjection.side").value("LANDLORD"))
        .andExpect(jsonPath("$.closeObjection.reason").value("아직 물이 새요"))
        .andExpect(jsonPath("$.closeObjection.at").exists());
    // 이견 뒤에도 늦은 종료 확인을 할 수 있다
    detail(caseId, landlord)
        .andExpect(jsonPath("$.availableActions", hasItems("confirm-close", "not-yet")));
  }

  @Test
  void REP_5f_이견_뒤에도_늦게_종료를_확인하면_양측_확인이_되고_이견은_이력이_된다() throws Exception {
    UUID caseId = closedAloneThenLandlordJoins();
    act(caseId, landlord, "not-yet", "{\"reason\":\"아직 물이 새요\"}").andExpect(status().isOk());

    act(caseId, landlord, "confirm-close", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closedBy").value("BOTH"));

    detail(caseId, tenant)
        .andExpect(jsonPath("$.closedBy").value("BOTH"))
        .andExpect(jsonPath("$.closeObjection").value(nullValue()));
    detail(caseId, landlord)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-close"))))
        .andExpect(jsonPath("$.availableActions", not(hasItem("not-yet"))));
  }

  @Test
  void 혼자_끝낸_측은_스스로_확인하거나_이견을_남길_수_없다() throws Exception {
    UUID caseId = closedAloneThenLandlordJoins();

    act(caseId, tenant, "confirm-close", null)
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    act(caseId, tenant, "not-yet", "{\"reason\":\"사유\"}")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    act(caseId, landlord, "not-yet", null).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
  }

  @Test
  void 거주가_끝난_뒤에도_혼자_끝낸_건에_늦게_답할_수_있다() throws Exception {
    UUID caseId = closedAloneThenLandlordJoins();
    endRoom();

    act(caseId, landlord, "not-yet", "{\"reason\":\"이사 전까지 안 고쳐졌어요\"}").andExpect(status().isOk());
    act(caseId, landlord, "confirm-close", null).andExpect(jsonPath("$.closedBy").value("BOTH"));
  }

  // ---- 준비물 ----

  /** 상대(집주인) 자리가 비어 있을 때 세입자가 혼자 끝내고, 그 뒤 집주인이 연결한다. */
  private UUID closedAloneThenLandlordJoins() throws Exception {
    emptySeat(PartySide.LANDLORD);
    UUID caseId = started();
    act(caseId, tenant, "close-alone", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'CONNECTED', member_id = ?, connected_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        landlord,
        room);
    return caseId;
  }

  private UUID started() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    return caseId;
  }

  private ResultActions act(UUID caseId, UUID memberId, String action, String json)
      throws Exception {
    var request =
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID());
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(request);
  }

  private ResultActions closeAlone(UUID caseId, String key) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/close-alone", caseId)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key));
  }

  private void respond(UUID caseId, UUID memberId, String answer, String reason) throws Exception {
    mvc.perform(
            post("/api/repair-cases/{id}/respond", caseId)
                .with(loginAs(memberId))
                .header("Idempotency-Key", "res-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"answer\":\""
                        + answer
                        + "\",\"reason\":\""
                        + reason
                        + "\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());
  }

  /** 그 행동의 강행 표시(payload {@code while_open_objection}). 없으면 null. */
  private String objectionMark(UUID caseId, String type) {
    return jdbc.queryForObject(
        "SELECT payload ->> 'while_open_objection' FROM record.action"
            + " WHERE repair_case_id = ? AND type = ?",
        String.class,
        caseId,
        type);
  }

  private long countActions(UUID caseId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE repair_case_id = ? AND type = ?",
        Long.class,
        caseId,
        type);
  }

  private ResultActions detail(UUID caseId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)))
        .andExpect(status().isOk());
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register() throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\"천장 누수\",\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private void endRoom() {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
  }

  private static RequestTarget caseRequest(UUID caseId) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, caseId);
  }

  private Map<String, Object> caseRow(UUID caseId) {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  /** 혼자 종료 행동의 혼자 진행 이유(payload {@code solo_reason}). */
  private String soloReason(UUID caseId) {
    return jdbc.queryForObject(
        "SELECT payload ->> 'solo_reason' FROM record.action"
            + " WHERE repair_case_id = ? AND type = 'CLOSE_ALONE'",
        String.class,
        caseId);
  }

  private UUID insertRoom() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
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

  private void connected(PartySide side, UUID memberId) {
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

  private void emptySeat(PartySide side) {
    seatWithoutMember(side, "EMPTY", null);
  }

  private void numberOnlySeat(PartySide side) {
    seatWithoutMember(side, "NUMBER_ONLY", "01012345678");
  }

  private void invitedSeat(PartySide side) {
    seatWithoutMember(side, "INVITED", "01012345678");
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

  private UUID part() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room);
    return id;
  }
}
