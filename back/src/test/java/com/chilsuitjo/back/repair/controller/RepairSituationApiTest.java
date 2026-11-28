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
 * 06 REP-6d [상황이 바뀌었어요] 확인 요청과 상황 변화 응답을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#256). 응답 요청은
 * 가짜({@code FakeWaitService})라 상태만 확인한다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairSituationApiTest {

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

  // ---- [상황이 바뀌었어요] 켜기 ----

  @Test
  void REP_6d_하자를_둘_다_확인한_건에_켜서_남기면_상대에게_상황_변화_확인_요청이_열린다() throws Exception {
    UUID caseId = acked();

    UUID noteId = createdId(asksCheck(caseId, tenant).andExpect(status().isCreated()));

    assertThat(noteRow(noteId)).containsEntry("asks_check", true);
    assertThat(waits.statusOf(noteRequest(noteId), RequestKind.SITUATION_CHECK))
        .contains(RequestStatus.WAITING);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload ->> 'asksCheck' FROM record.action"
                    + " WHERE repair_case_id = ? AND type = 'NOTE_PROGRESS'",
                String.class,
                caseId))
        .isEqualTo("true");
    // 하자 [맞아요]는 그대로다
    assertThat(caseRow(caseId)).containsEntry("defect_ack", "ACK");
  }

  @Test
  void 하자를_둘_다_확인하기_전이면_INVALID_STATE이고_아무것도_남지_않는다() throws Exception {
    UUID caseId = register();

    asksCheck(caseId, tenant)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    assertThat(noteCount(caseId)).isZero();
    assertThat(countActions(caseId, "NOTE_PROGRESS")).isZero();
  }

  @Test
  void 보충하고_7일_안에_맞아요가_와도_다시_요청끼리라_ASKED_RECENTLY() throws Exception {
    // 03 G "다시 확인 요청의 한도", 06 0-1 rerequest.interval: 보충과 [상황이 바뀌었어요]를 함께 센다(#259 리뷰)
    UUID caseId = register();
    act(
            caseId,
            landlord,
            "respond",
            "{\"answer\":\"NEED_MORE\",\"reason\":\"사진이 흐려요\",\"seenVersionNo\":1}")
        .andExpect(status().isOk());
    act(caseId, tenant, "supplement", "{\"description\":\"밝게 다시 찍었어요\"}")
        .andExpect(status().isOk());
    act(caseId, landlord, "respond", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isOk());

    asksCheck(caseId, tenant).andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    assertThat(noteCount(caseId)).isZero();
  }

  @Test
  void 누가_켰든_한_건에_7일에_한_번이고_켜지_않은_진행_상황은_그대로_남는다() throws Exception {
    UUID caseId = acked();
    asksCheck(caseId, tenant).andExpect(status().isCreated());

    asksCheck(caseId, landlord).andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    note(caseId, landlord, "{\"text\":\"내일 업체가 와요\"}").andExpect(status().isCreated());

    assertThat(noteCount(caseId)).isEqualTo(2);
  }

  @Test
  void 거주가_끝나면_켤_수_없지만_진행_상황은_남길_수_있다() throws Exception {
    UUID caseId = acked();
    endRoom();

    asksCheck(caseId, tenant).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    note(caseId, tenant, "{\"text\":\"이사 전에 다시 봤어요\"}").andExpect(status().isCreated());
  }

  @Test
  void 공유를_그만둔_사람은_SHARING_STOPPED이고_상대가_그만둔_동안은_INVALID_STATE() throws Exception {
    // 03 B: 그만둔 사람은 진행 상황(나만 보는 기록)만 남긴다. 상대가 그만둔 뒤 남긴 기록은 상대에게 보이지 않아 답할 수 없다(불변식 18)
    UUID caseId = acked();
    stopSharing(PartySide.TENANT);

    asksCheck(caseId, tenant).andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    note(caseId, tenant, "{\"text\":\"혼자 적어 둘게요\"}").andExpect(status().isCreated());
    asksCheck(caseId, landlord).andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // ---- 상황 변화 응답 ----

  @Test
  void 상대가_맞아요로_답하면_지금_답이_남고_요청이_닫히며_하자_인정은_그대로다() throws Exception {
    UUID noteId = askedNote();

    respond(noteId, landlord, "{\"answer\":\"ACK\"}")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RepairController.REPLAYED))
        .andExpect(jsonPath("$.targetId").value(noteId.toString()))
        .andExpect(jsonPath("$.versionNo").doesNotExist())
        .andExpect(jsonPath("$.state").value("ACK"));

    assertThat(responseRows(noteId))
        .singleElement()
        .satisfies(row ->
            assertThat(row).containsEntry("side", "LANDLORD").containsEntry("answer", "ACK"));
    assertThat(waits.statusOf(noteRequest(noteId), RequestKind.SITUATION_CHECK))
        .contains(RequestStatus.ANSWERED);
    Map<String, Object> action =
        jdbc.queryForMap(
            "SELECT * FROM record.action WHERE progress_note_id = ? AND type = 'RESPOND_SITUATION'",
            noteId);
    assertThat(action).containsEntry("actor_side", "LANDLORD");
    // 답한 진행 상황이 내용 지문이다(안내서 3-3)
    assertThat(action.get("content_digest")).isNotNull();
    assertThat(caseRow(caseOf(noteId))).containsEntry("defect_ack", "ACK");
  }

  @Test
  void 보류는_사유와_함께_남고_맞아요로만_바꾸며_맞아요_뒤에는_ACK_FINAL() throws Exception {
    UUID noteId = askedNote();

    respond(noteId, landlord, "{\"answer\":\"NEED_MORE\"}")
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    respond(noteId, landlord, "{\"answer\":\"NEED_MORE\",\"reason\":\"사진이 흐려요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NEED_MORE"));
    respond(noteId, landlord, "{\"answer\":\"DISPUTE\",\"reason\":\"다른 곳이에요\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    respond(noteId, landlord, "{\"answer\":\"ACK\"}").andExpect(jsonPath("$.state").value("ACK"));
    respond(noteId, landlord, "{\"answer\":\"ACK\"}")
        .andExpect(jsonPath("$.code").value("ACK_FINAL"));

    // 지금 답 하나만 두고 바뀐 이력은 행동 기록에
    assertThat(responseRows(noteId))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("answer", "ACK"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action"
                    + " WHERE progress_note_id = ? AND type = 'RESPOND_SITUATION'",
                Long.class,
                noteId))
        .isEqualTo(2);
  }

  @Test
  void 남긴_측은_답할_수_없고_켜지_않은_진행_상황에는_답할_것이_없다() throws Exception {
    UUID noteId = askedNote();
    respond(noteId, tenant, "{\"answer\":\"ACK\"}")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));

    UUID plain =
        createdId(
            note(caseOf(noteId), tenant, "{\"text\":\"그냥 적어요\"}").andExpect(status().isCreated()));
    respond(plain, landlord, "{\"answer\":\"ACK\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(responseRows(plain)).isEmpty();
  }

  @Test
  void REP_6c_상황_변화에_답하면_물어_둔_진행_상황_묻기도_닫힌다() throws Exception {
    UUID noteId = askedNote();
    UUID caseId = caseOf(noteId);
    act(caseId, tenant, "ask-progress", null).andExpect(status().isOk());

    respond(noteId, landlord, "{\"answer\":\"ACK\"}").andExpect(status().isOk());

    assertThat(
            waits.statusOf(
                RequestTarget.of(TargetKind.REPAIR_CASE, caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 거주가_끝난_뒤에도_정산_완료_전까지_답할_수_있다() throws Exception {
    UUID noteId = askedNote();
    endRoom();

    respond(noteId, landlord, "{\"answer\":\"DISPUTE\",\"reason\":\"고쳐지지 않았어요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISPUTE"));
  }

  @Test
  void 같은_키로_다시_보내면_행동을_더_남기지_않고_지금_답을_돌려준다() throws Exception {
    UUID noteId = askedNote();

    respondWithKey(noteId, landlord, "same", "{\"answer\":\"ACK\"}").andExpect(status().isOk());
    respondWithKey(noteId, landlord, "same", "{\"answer\":\"ACK\"}")
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.state").value("ACK"));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE progress_note_id = ?",
                Long.class,
                noteId))
        .isEqualTo(1);
  }

  @Test
  void 없는_진행_상황_당사자가_아님_볼_수_없는_진행_상황은_NOT_FOUND() throws Exception {
    UUID noteId = askedNote();
    respond(UUID.randomUUID(), landlord, "{\"answer\":\"ACK\"}").andExpect(status().isNotFound());
    respond(noteId, member(), "{\"answer\":\"ACK\"}").andExpect(status().isNotFound());

    // 세입자가 공유를 그만둔 뒤 남긴 진행 상황은 집주인에게 보이지 않는다(불변식 18)
    UUID caseId = caseOf(noteId);
    stopSharing(PartySide.TENANT);
    UUID hidden =
        createdId(note(caseId, tenant, "{\"text\":\"혼자 적어요\"}").andExpect(status().isCreated()));
    respond(hidden, landlord, "{\"answer\":\"ACK\"}").andExpect(status().isNotFound());
  }

  @Test
  void 답이_없거나_사유에_주민번호_형태가_있으면_남기지_않는다() throws Exception {
    UUID noteId = askedNote();

    respond(noteId, landlord, "{}").andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    respond(noteId, landlord, "{\"answer\":\"DISPUTE\",\"reason\":\"900101-1234567\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(responseRows(noteId)).isEmpty();
  }

  // ---- 준비물 ----

  /** 세입자가 등록하고 집주인이 [맞아요]로 답한 수리 건. */
  // ---- 수리 건 상세의 진행 상황(06 4-6 progressNotes, #367) ----

  @Test
  void 상세에_진행_상황이_남긴_순서로_보이고_상대만_상황_변화에_답할_수_있다() throws Exception {
    UUID caseId = acked();
    note(caseId, landlord, "{\"text\":\"내일 업체가 와요\"}").andExpect(status().isCreated());
    UUID asked = createdId(asksCheck(caseId, tenant).andExpect(status().isCreated()));

    detail(caseId, landlord)
        .andExpect(jsonPath("$.progressNotes.length()").value(2))
        .andExpect(jsonPath("$.progressNotes[0].side").value("LANDLORD"))
        .andExpect(jsonPath("$.progressNotes[0].text").value("내일 업체가 와요"))
        .andExpect(jsonPath("$.progressNotes[0].asksCheck").value(false))
        .andExpect(jsonPath("$.progressNotes[0].canRespond").value(false))
        .andExpect(jsonPath("$.progressNotes[0].photos").isArray())
        .andExpect(jsonPath("$.progressNotes[1].id").value(asked.toString()))
        .andExpect(jsonPath("$.progressNotes[1].asksCheck").value(true))
        .andExpect(jsonPath("$.progressNotes[1].response").doesNotExist())
        .andExpect(jsonPath("$.progressNotes[1].canRespond").value(true));
    // 남긴 측은 답할 수 없다
    detail(caseId, tenant).andExpect(jsonPath("$.progressNotes[1].canRespond").value(false));
  }

  @Test
  void 보류로_답하면_답과_사유가_보이고_맞아요로_바꾸면_더_답할_수_없다() throws Exception {
    UUID noteId = askedNote();
    UUID caseId = caseOf(noteId);
    respond(noteId, landlord, "{\"answer\":\"NEED_MORE\",\"reason\":\"사진이 더 필요해요\"}")
        .andExpect(status().isOk());

    detail(caseId, landlord)
        .andExpect(jsonPath("$.progressNotes[0].response.answer").value("NEED_MORE"))
        .andExpect(jsonPath("$.progressNotes[0].response.reason").value("사진이 더 필요해요"))
        .andExpect(jsonPath("$.progressNotes[0].response.at").exists())
        .andExpect(jsonPath("$.progressNotes[0].canRespond").value(true));

    respond(noteId, landlord, "{\"answer\":\"ACK\"}").andExpect(status().isOk());
    detail(caseId, tenant)
        .andExpect(jsonPath("$.progressNotes[0].response.answer").value("ACK"))
        .andExpect(jsonPath("$.progressNotes[0].response.reason").doesNotExist());
    detail(caseId, landlord).andExpect(jsonPath("$.progressNotes[0].canRespond").value(false));
  }

  @Test
  void 공유를_그만둔_뒤에는_그_시각까지와_내_진행_상황만_보인다() throws Exception {
    // 공유를 그만둔 사람에게는 그 시각까지 있던 부위만 보인다. 행동 없이 복사되는 기본 부위로 둔다(PartVisibility)
    jdbc.update(
        "UPDATE state.room_part SET template_id ="
            + " (SELECT id FROM state.part_template WHERE zone = '방' AND name = '천장') WHERE id = ?",
        part);
    UUID caseId = acked();
    UUID before = createdId(note(caseId, landlord, "{\"text\":\"그만두기 전\"}"));
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at FROM record.progress_note WHERE id = ?) + interval '1 millisecond'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        before,
        room);
    note(caseId, landlord, "{\"text\":\"그만둔 뒤\"}").andExpect(status().isCreated());
    note(caseId, tenant, "{\"text\":\"내가 남긴 것\"}").andExpect(status().isCreated());

    detail(caseId, tenant)
        .andExpect(jsonPath("$.progressNotes.length()").value(2))
        .andExpect(jsonPath("$.progressNotes[0].text").value("그만두기 전"))
        .andExpect(jsonPath("$.progressNotes[1].text").value("내가 남긴 것"));
    // 집주인은 공유를 그만둔 상대가 등록한 수리 건을 볼 수 없어(불변식 18) 상세가 없다
  }

  private ResultActions detail(UUID caseId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)))
        .andExpect(status().isOk());
  }

  private UUID acked() throws Exception {
    UUID caseId = register();
    act(caseId, landlord, "respond", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isOk());
    return caseId;
  }

  /** 세입자가 [상황이 바뀌었어요]를 켜서 남긴 진행 상황. */
  private UUID askedNote() throws Exception {
    return createdId(asksCheck(acked(), tenant).andExpect(status().isCreated()));
  }

  private ResultActions asksCheck(UUID caseId, UUID memberId) throws Exception {
    return note(caseId, memberId, "{\"text\":\"구멍이 더 커졌어요\",\"asksCheck\":true}");
  }

  private ResultActions note(UUID caseId, UUID memberId, String json) throws Exception {
    return act(caseId, memberId, "progress-notes", json);
  }

  private ResultActions respond(UUID noteId, UUID memberId, String json) throws Exception {
    return respondWithKey(noteId, memberId, "sit-" + UUID.randomUUID(), json);
  }

  private ResultActions respondWithKey(UUID noteId, UUID memberId, String key, String json)
      throws Exception {
    return mvc.perform(
        post("/api/progress-notes/{id}/respond", noteId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
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

  private static UUID createdId(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register() throws Exception {
    return createdId(
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
            .andExpect(status().isCreated()));
  }

  private void endRoom() {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
  }

  private void stopSharing(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = ?",
        room,
        side.name());
  }

  private static RequestTarget noteRequest(UUID noteId) {
    return RequestTarget.of(TargetKind.PROGRESS_NOTE, noteId);
  }

  private UUID caseOf(UUID noteId) {
    return jdbc.queryForObject(
        "SELECT repair_case_id FROM record.progress_note WHERE id = ?", UUID.class, noteId);
  }

  private Map<String, Object> caseRow(UUID caseId) {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  private Map<String, Object> noteRow(UUID noteId) {
    return jdbc.queryForMap("SELECT * FROM record.progress_note WHERE id = ?", noteId);
  }

  private List<Map<String, Object>> responseRows(UUID noteId) {
    return jdbc.queryForList(
        "SELECT * FROM state.progress_note_response WHERE progress_note_id = ?", noteId);
  }

  private long noteCount(UUID caseId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.progress_note WHERE repair_case_id = ?", Long.class, caseId);
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
