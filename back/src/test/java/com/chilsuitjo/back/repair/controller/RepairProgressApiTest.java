package com.chilsuitjo.back.repair.controller;

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

/**
 * 06 REP-4·5 수리 시작·종료 요청·종료 확인·아직 안 됐어요를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#174). 응답 요청은
 * 가짜({@code FakeWaitService})라 상태만 확인한다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairProgressApiTest {

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
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
  }

  // ---- 수리 시작 ----

  @Test
  void REP_4a_확인_전이어도_상대가_수리를_시작하면_처리_중이_되고_하자_인정은_그대로다() throws Exception {
    UUID caseId = register();

    act(caseId, landlord, "start", null)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RepairController.REPLAYED))
        .andExpect(jsonPath("$.id").value(caseId.toString()))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.defectAck").value("PENDING"))
        .andExpect(jsonPath("$.closedBy").doesNotExist());

    assertThat(caseRow(caseId))
        .containsEntry("status", "IN_PROGRESS")
        .containsEntry("defect_ack", "PENDING");
    Map<String, Object> action = action(caseId, "START_REPAIR");
    assertThat(action).containsEntry("actor_side", "LANDLORD");
    // 판을 보지 않는 행동이라 내용 지문이 없다(안내서 3-3)
    assertThat(action.get("content_digest")).isNull();
  }

  @Test
  void 보류가_열린_채_시작하고_종료하면_행동마다_강행_표시를_남긴다() throws Exception {
    // 03 G "강행 표시", 05 2-3 payload while_open_objection
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요");

    act(caseId, tenant, "start", null).andExpect(status().isOk());
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());
    act(caseId, landlord, "confirm-close", null).andExpect(status().isOk());

    assertThat(objectionMark(caseId, "START_REPAIR")).isEqualTo("NEED_MORE");
    assertThat(objectionMark(caseId, "REQUEST_CLOSE")).isEqualTo("NEED_MORE");
    assertThat(objectionMark(caseId, "CONFIRM_CLOSE")).isEqualTo("NEED_MORE");
  }

  @Test
  void 이견이_열린_채_시작하면_이견으로_남고_확인_전이나_맞아요면_남기지_않는다() throws Exception {
    UUID disputed = register();
    respond(disputed, landlord, "DISPUTE", "원래 있던 자국이에요");
    act(disputed, tenant, "start", null).andExpect(status().isOk());
    assertThat(objectionMark(disputed, "START_REPAIR")).isEqualTo("DISPUTE");

    // 확인 전("○○ 님 확인 전에 시작했어요")은 하자 인정으로 보이고 payload에는 넣지 않는다(05 2-3은 NEED_MORE/DISPUTE만)
    UUID pending = register();
    act(pending, landlord, "start", null).andExpect(status().isOk());
    assertThat(objectionMark(pending, "START_REPAIR")).isNull();

    UUID acked = register();
    respond(acked, landlord, "ACK", null);
    act(acked, tenant, "start", null).andExpect(status().isOk());
    assertThat(objectionMark(acked, "START_REPAIR")).isNull();
  }

  @Test
  void 시작_전이_아니면_다시_시작할_수_없다() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());

    act(caseId, landlord, "start", null).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(countActions(caseId, "START_REPAIR")).isEqualTo(1);
  }

  // ---- 종료 요청 → 종료 확인 ----

  @Test
  void 종료를_요청하면_종료_확인_대기가_되고_상대에게_종료_확인_요청이_열린다() throws Exception {
    UUID caseId = started();

    act(caseId, tenant, "request-close", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"));

    assertThat(caseRow(caseId)).containsEntry("status", "CLOSE_WAIT");
    assertThat(waits.statusOf(closeRequest(caseId), RequestKind.CLOSE_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void REP_5a_요청의_상대가_종료를_확인하면_양측_확인으로_끝나고_요청이_닫힌다() throws Exception {
    UUID caseId = closeRequested(tenant);

    act(caseId, landlord, "confirm-close", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closedBy").value("BOTH"));

    assertThat(caseRow(caseId)).containsEntry("status", "DONE").containsEntry("closed_by", "BOTH");
    assertThat(waits.statusOf(closeRequest(caseId), RequestKind.CLOSE_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void REP_3a_하자가_확인_기다림이면_종료_확인과_하자_맞아요를_한_요청에_순서대로_남긴다() throws Exception {
    UUID caseId = closeRequested(tenant);

    act(caseId, landlord, "confirm-close", "{\"alsoAckDefect\":true,\"seenVersionNo\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.defectAck").value("ACK"));

    List<String> types =
        jdbc.queryForList(
            "SELECT type FROM record.action WHERE repair_case_id = ? AND type IN"
                + " ('CONFIRM_CLOSE', 'RESPOND_DEFECT') ORDER BY room_seq",
            String.class,
            caseId);
    assertThat(types).containsExactly("CONFIRM_CLOSE", "RESPOND_DEFECT");
    Map<String, Object> respond = action(caseId, "RESPOND_DEFECT");
    assertThat(respond).containsEntry("seen_version_no", 1);
    assertThat(respond.get("content_digest")).isNotNull(); // 본 판이 지문이다
    Map<String, Object> row = caseRow(caseId);
    assertThat(row).containsEntry("defect_ack", "ACK");
    assertThat(row.get("confirmed_content_id")).isEqualTo(row.get("current_content_id"));
  }

  @Test
  void 하자가_이미_답해졌으면_함께_맞아요를_남기지_않는다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요");
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());

    act(caseId, landlord, "confirm-close", "{\"alsoAckDefect\":true,\"seenVersionNo\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defectAck").value("NEED_MORE"));

    assertThat(countActions(caseId, "RESPOND_DEFECT")).isEqualTo(1);
  }

  @Test
  void 하자를_등록한_측이_종료를_확인하면_함께_맞아요를_남기지_않고_종료_확인만_남긴다() throws Exception {
    // 세입자가 등록하고 집주인이 종료를 요청하면 종료 확인은 세입자 몫이다. 세입자는 자기 하자에 맞아요로 답할 수 없다(03 3장)
    UUID caseId = closeRequested(landlord);

    act(caseId, tenant, "confirm-close", "{\"alsoAckDefect\":true,\"seenVersionNo\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.defectAck").value("PENDING"));

    assertThat(countActions(caseId, "CONFIRM_CLOSE")).isEqualTo(1);
    assertThat(countActions(caseId, "RESPOND_DEFECT")).isZero();
    assertThat(caseRow(caseId))
        .containsEntry("status", "DONE")
        .containsEntry("defect_ack", "PENDING");
  }

  @Test
  void 함께_남길_하자_응답의_판_번호가_다르면_종료_확인도_남지_않는다() throws Exception {
    UUID caseId = closeRequested(tenant);

    act(caseId, landlord, "confirm-close", "{\"alsoAckDefect\":true,\"seenVersionNo\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    assertThat(caseRow(caseId))
        .containsEntry("status", "CLOSE_WAIT")
        .containsEntry("defect_ack", "PENDING");
    assertThat(countActions(caseId, "CONFIRM_CLOSE")).isZero();
    // 첫 단계(종료 확인)가 닫은 요청도 함께 되돌아가 상대는 다시 답할 수 있다(#177 리뷰)
    assertThat(waits.statusOf(closeRequest(caseId), RequestKind.CLOSE_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 종료를_요청한_측은_스스로_확인할_수_없다() throws Exception {
    UUID caseId = closeRequested(tenant);

    act(caseId, tenant, "confirm-close", null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  // ---- 아직 안 됐어요 ----

  @Test
  void REP_5b_아직_안_됐어요는_사유와_함께_처리_중으로_돌아가고_요청이_닫힌다() throws Exception {
    UUID caseId = closeRequested(landlord);

    act(caseId, tenant, "not-yet", "{\"reason\":\"아직 물이 새요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    assertThat(action(caseId, "NOT_YET")).containsEntry("reason", "아직 물이 새요");
    assertThat(waits.statusOf(closeRequest(caseId), RequestKind.CLOSE_CONFIRM))
        .contains(RequestStatus.ANSWERED);
    // 다시 종료를 요청할 수 있다
    act(caseId, landlord, "request-close", null)
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"));
  }

  @Test
  void 아직_안_됐어요에_사유가_없으면_REASON_REQUIRED이고_주민번호_형태는_막는다() throws Exception {
    UUID caseId = closeRequested(landlord);

    act(caseId, tenant, "not-yet", "{\"reason\":\"  \"}")
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    act(caseId, tenant, "not-yet", null).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    act(caseId, tenant, "not-yet", "{\"reason\":\"제 번호 900101-1234567\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(caseRow(caseId)).containsEntry("status", "CLOSE_WAIT");
    assertThat(countActions(caseId, "NOT_YET")).isZero();
  }

  // ---- 거주 종료 뒤 ----

  @Test
  void 거주가_끝나면_종료를_요청할_수_없지만_열린_요청에는_늦게_확인할_수_있다() throws Exception {
    UUID started = started();
    UUID waiting = closeRequested(tenant);
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    act(started, tenant, "request-close", null)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    act(waiting, landlord, "confirm-close", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"));
  }

  @Test
  void 거주가_끝나면_시작_전인_수리를_시작할_수_없고_버튼도_없다() throws Exception {
    // 03 A "상태별로 할 수 있는 일": 거주 종료 뒤 수리 상태는 "거주 종료 시점"으로 멈춘다(#177 리뷰)
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    act(caseId, landlord, "start", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_ENDED"));
    assertThat(countActions(caseId, "START_REPAIR")).isZero();
    assertThat(caseRow(caseId)).containsEntry("status", "NOT_STARTED");
    detail(caseId, landlord)
        .andExpect(status().isOk())
        // 하자 응답은 정산 완료 전까지 되므로(03 A) 그것과 진행 상황 남기기(언제든, 03 D)만 남는다
        .andExpect(jsonPath("$.availableActions.length()").value(2))
        .andExpect(jsonPath("$.availableActions[0]").value("respond"))
        .andExpect(jsonPath("$.availableActions[1]").value("progress-notes"));
  }

  // ---- 상세의 버튼과 같은 키 ----

  @Test
  void 상세의_버튼이_수리_진행에_따라_바뀐다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null);

    detail(caseId, tenant)
        .andExpect(jsonPath("$.availableActions[0]").value("start"))
        .andExpect(jsonPath("$.closeRequestedBy").doesNotExist());
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    detail(caseId, landlord).andExpect(jsonPath("$.availableActions[0]").value("request-close"));
    act(caseId, landlord, "request-close", null).andExpect(status().isOk());
    // 요청한 쪽에는 진행 상황 남기기만 있고 상대에게는 종료 확인·아직 안 됐어요가 보인다. 누가 요청했는지는 양쪽 모두 본다.
    // 종료 확인 대기·처리 완료에서는 진행 상황을 물을 수 없다(03 D "시작 전·처리 중")
    detail(caseId, landlord)
        .andExpect(jsonPath("$.availableActions.length()").value(1))
        .andExpect(jsonPath("$.availableActions[0]").value("progress-notes"))
        .andExpect(jsonPath("$.closeRequestedBy").value("LANDLORD"));
    detail(caseId, tenant)
        .andExpect(jsonPath("$.closeRequestedBy").value("LANDLORD"))
        .andExpect(jsonPath("$.availableActions.length()").value(3))
        .andExpect(jsonPath("$.availableActions[0]").value("confirm-close"))
        .andExpect(jsonPath("$.availableActions[1]").value("not-yet"))
        .andExpect(jsonPath("$.availableActions[2]").value("progress-notes"));
    act(caseId, tenant, "confirm-close", null).andExpect(status().isOk());
    detail(caseId, tenant)
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closeRequestedBy").doesNotExist())
        .andExpect(jsonPath("$.availableActions.length()").value(1))
        .andExpect(jsonPath("$.availableActions[0]").value("progress-notes"));
  }

  @Test
  void 같은_키로_다시_보내면_행동을_더_남기지_않고_지금_상태를_돌려준다() throws Exception {
    UUID caseId = register();
    actWithKey(caseId, tenant, "start", "same", null).andExpect(status().isOk());

    actWithKey(caseId, tenant, "start", "same", null)
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    assertThat(countActions(caseId, "START_REPAIR")).isEqualTo(1);

    UUID waiting = closeRequested(tenant);
    actWithKey(waiting, landlord, "confirm-close", "c1", null).andExpect(status().isOk());
    actWithKey(waiting, landlord, "confirm-close", "c1", null)
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.status").value("DONE"));
    assertThat(countActions(waiting, "CONFIRM_CLOSE")).isEqualTo(1);
  }

  @Test
  void 없는_수리_건이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID caseId = register();

    act(UUID.randomUUID(), tenant, "start", null).andExpect(status().isNotFound());
    act(caseId, member(), "start", null).andExpect(status().isNotFound());
  }

  // ---- 준비물 ----

  private UUID started() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    return caseId;
  }

  private UUID closeRequested(UUID requester) throws Exception {
    UUID caseId = started();
    act(caseId, requester, "request-close", null).andExpect(status().isOk());
    return caseId;
  }

  private ResultActions act(UUID caseId, UUID memberId, String action, String json)
      throws Exception {
    return actWithKey(caseId, memberId, action, action + "-" + UUID.randomUUID(), json);
  }

  private ResultActions actWithKey(
      UUID caseId, UUID memberId, String action, String key, String json) throws Exception {
    var request =
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key);
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(request);
  }

  private ResultActions detail(UUID caseId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)));
  }

  private void respond(UUID caseId, UUID memberId, String answer, String reason) throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":1}";
    mvc.perform(
            post("/api/repair-cases/{id}/respond", caseId)
                .with(loginAs(memberId))
                .header("Idempotency-Key", "res-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
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

  private static RequestTarget closeRequest(UUID caseId) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, caseId);
  }

  private Map<String, Object> caseRow(UUID caseId) {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  private Map<String, Object> action(UUID caseId, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE repair_case_id = ? AND type = ?", caseId, type);
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
