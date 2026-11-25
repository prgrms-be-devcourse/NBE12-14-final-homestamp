package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.PartySide;
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
 * 06 REP-8 원인 설명을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 사진은 가짜 사진 창구({@code
 * FakePhotoService})가 받는다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CauseNoteApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;
  private UUID caseId;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    part = part();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    caseId = register();
  }

  // ---- 남기기 ----

  @Test
  void REP_8a_근거_종류_없이_남기면_BASIS_REQUIRED() throws Exception {
    note(tenant, "n1", "{\"text\":\"윗집 배관 같아요\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BASIS_REQUIRED"));

    assertThat(count("record.cause_note")).isZero();
  }

  @Test
  void 설명을_남기면_새_설명이_대상인_행동과_내용_지문이_남고_상대에게_응답_버튼이_보인다() throws Exception {
    UUID noteId =
        created(
            note(tenant, "n1", "{\"text\":\"윗집 욕실 배관 누수로 보여요\",\"basis\":\"GUESS\"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(RepairController.REPLAYED)));

    Map<String, Object> row = noteRow(noteId);
    assertThat(row)
        .containsEntry("repair_case_id", caseId)
        .containsEntry("side", "TENANT")
        .containsEntry("basis", "GUESS");
    assertThat(row.get("previous_id")).isNull();
    Map<String, Object> action = actionOf(noteId, "NOTE_CAUSE");
    assertThat(action).containsEntry("actor_side", "TENANT");
    assertThat(action.get("payload")).isNull();
    assertThat((byte[]) action.get("content_digest"))
        .isEqualTo(ContentDigest.compute(digest(noteId, "윗집 욕실 배관 누수로 보여요", "GUESS", null, null)));

    detail(landlord)
        .andExpect(jsonPath("$.causeNotes", hasSize(1)))
        .andExpect(jsonPath("$.causeNotes[0].id").value(noteId.toString()))
        .andExpect(jsonPath("$.causeNotes[0].side").value("TENANT"))
        .andExpect(jsonPath("$.causeNotes[0].basis").value("GUESS"))
        .andExpect(jsonPath("$.causeNotes[0].response").value(nullValue()))
        .andExpect(jsonPath("$.causeNotes[0].canRespond").value(true))
        .andExpect(jsonPath("$.causeNotes[0].previous", hasSize(0)))
        .andExpect(jsonPath("$.availableActions", hasItem("cause-notes")));
    // 내가 남긴 설명에는 답하지 않는다
    detail(tenant).andExpect(jsonPath("$.causeNotes[0].canRespond").value(false));
  }

  @Test
  void 업체_소견이면_업체_이름과_근거_사진이_설명에_붙는다() throws Exception {
    UUID noteId =
        created(
            note(
                    landlord,
                    "n1",
                    "{\"text\":\"배관 노후\",\"basis\":\"EXPERT\",\"expertName\":\"○○설비\","
                        + "\"uploadTickets\":[\""
                        + UUID.randomUUID()
                        + "\"]}")
                .andExpect(status().isCreated()));

    assertThat(noteRow(noteId)).containsEntry("expert_name", "○○설비");
    assertThat(actionOf(noteId, "NOTE_CAUSE").get("payload").toString()).contains("photos");
    detail(tenant)
        .andExpect(jsonPath("$.causeNotes[0].expertName").value("○○설비"))
        .andExpect(jsonPath("$.causeNotes[0].photos", hasSize(1)))
        .andExpect(jsonPath("$.causeNotes[0].photos[0].stage").value("BASIS"));
  }

  @Test
  void 입력이_잘못되면_VALIDATION_FAILED_주민번호_형태면_RESIDENT_ID_BLOCKED() throws Exception {
    note(tenant, "n1", "{\"basis\":\"SEEN\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    note(tenant, "n2", "{\"text\":\"봤어요\",\"basis\":\"SEEN\",\"expertName\":\"○○설비\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    note(tenant, "n3", "{\"text\":\"" + "가".repeat(2001) + "\",\"basis\":\"SEEN\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    note(tenant, "n4", "{\"text\":\"900101-1234567 관리인이 말함\",\"basis\":\"HEARD\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(count("record.cause_note")).isZero();
  }

  @Test
  void 같은_키로_다시_오면_처음_만든_설명을_돌려준다() throws Exception {
    String body = "{\"text\":\"직접 봤어요\",\"basis\":\"SEEN\"}";
    UUID first = created(note(tenant, "n1", body).andExpect(status().isCreated()));

    UUID again =
        created(
            note(tenant, "n1", body)
                .andExpect(status().isCreated())
                .andExpect(header().string(RepairController.REPLAYED, "true")));

    assertThat(again).isEqualTo(first);
    assertThat(count("record.cause_note")).isEqualTo(1);
  }

  // ---- 응답 ----

  @Test
  void REP_8b_원인_설명에_이견을_남겨도_하자_인정과_부담은_그대로다() throws Exception {
    UUID noteId = created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));

    respond(noteId, landlord, "r1", "DISPUTE", "우리 집 배관은 새것이에요")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetId").value(noteId.toString()))
        .andExpect(jsonPath("$.versionNo").value(nullValue()))
        .andExpect(jsonPath("$.state").value("DISPUTE"));

    Map<String, Object> action = actionOf(noteId, "RESPOND_CAUSE");
    assertThat(action)
        .containsEntry("actor_side", "LANDLORD")
        .containsEntry("reason", "우리 집 배관은 새것이에요");
    assertThat(action.get("payload").toString()).isEqualTo("{\"answer\": \"DISPUTE\"}");
    assertThat((byte[]) action.get("content_digest"))
        .isEqualTo(ContentDigest.compute(digest(noteId, "윗집 배관", "GUESS", null, null)));
    assertThat(jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId))
        .containsEntry("defect_ack", "PENDING")
        .containsEntry("money_status", "UNDECIDED");
    detail(tenant)
        .andExpect(jsonPath("$.causeNotes[0].response.answer").value("DISPUTE"))
        .andExpect(jsonPath("$.causeNotes[0].response.reason").value("우리 집 배관은 새것이에요"));
    // 보류·이견에서도 맞아요로는 바꿀 수 있다(AnswerSupport)
    detail(landlord).andExpect(jsonPath("$.causeNotes[0].canRespond").value(true));
  }

  @Test
  void 응답_규칙은_하자_응답과_같다() throws Exception {
    UUID noteId = created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));

    respond(noteId, tenant, "r1", "ACK", null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    respond(noteId, landlord, "r2", "NEED_MORE", null)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    respond(noteId, landlord, "r3", "ACK", null).andExpect(status().isOk());
    respond(noteId, landlord, "r4", "DISPUTE", "다시 보니 아니에요")
        .andExpect(jsonPath("$.code").value("ACK_FINAL"));
    detail(landlord).andExpect(jsonPath("$.causeNotes[0].canRespond").value(false));
  }

  // ---- 고치기 ----

  @Test
  void REP_8c_고치면_새_설명이_앞에_보이고_이전_설명과_응답은_이력에_남는다() throws Exception {
    UUID first = created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));
    respond(first, landlord, "r1", "ACK", null).andExpect(status().isOk());

    UUID second =
        created(
            note(
                    tenant,
                    "n2",
                    "{\"text\":\"업체가 윗집 배관이래요\",\"basis\":\"EXPERT\",\"previousId\":\""
                        + first
                        + "\"}")
                .andExpect(status().isCreated()));

    assertThat(noteRow(second)).containsEntry("previous_id", first);
    assertThat(actionOf(second, "NOTE_CAUSE").get("payload").toString())
        .isEqualTo("{\"previousId\": \"" + first + "\"}");
    detail(landlord)
        .andExpect(jsonPath("$.causeNotes", hasSize(1)))
        .andExpect(jsonPath("$.causeNotes[0].id").value(second.toString()))
        .andExpect(jsonPath("$.causeNotes[0].previousId").value(first.toString()))
        .andExpect(jsonPath("$.causeNotes[0].response").value(nullValue()))
        .andExpect(jsonPath("$.causeNotes[0].canRespond").value(true))
        .andExpect(jsonPath("$.causeNotes[0].previous", hasSize(1)))
        .andExpect(jsonPath("$.causeNotes[0].previous[0].id").value(first.toString()))
        .andExpect(jsonPath("$.causeNotes[0].previous[0].response.answer").value("ACK"));
    // 이전 설명에는 더 답하지 않는다
    respond(first, landlord, "r2", "DISPUTE", "이제 아니에요")
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void REP_8d_이미_고쳐진_설명에서_다시_고치거나_설명이_있는데_새로_남기면_VERSION_CONFLICT() throws Exception {
    UUID first = created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));
    note(tenant, "n2", "{\"text\":\"고친 설명\",\"basis\":\"GUESS\",\"previousId\":\"" + first + "\"}")
        .andExpect(status().isCreated());

    // 같은 이전 설명에서 갈라지는 두 번째 수정본
    note(tenant, "n3", "{\"text\":\"다른 수정\",\"basis\":\"SEEN\",\"previousId\":\"" + first + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    // 지금 설명이 있는데 고칠 설명 없이 새로 남기기
    note(tenant, "n4", "{\"text\":\"새 설명\",\"basis\":\"SEEN\"}")
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    assertThat(count("record.cause_note")).isEqualTo(2);
  }

  @Test
  void 측마다_지금_설명이_하나씩_보인다() throws Exception {
    created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));
    created(note(landlord, "n2", "{\"text\":\"세입자 사용 부주의\",\"basis\":\"HEARD\"}"));

    detail(tenant)
        .andExpect(jsonPath("$.causeNotes", hasSize(2)))
        .andExpect(jsonPath("$.causeNotes[0].side").value("TENANT"))
        .andExpect(jsonPath("$.causeNotes[0].canRespond").value(false))
        .andExpect(jsonPath("$.causeNotes[1].side").value("LANDLORD"))
        .andExpect(jsonPath("$.causeNotes[1].canRespond").value(true));
  }

  // ---- 막기 ----

  @Test
  void 당사자가_아니거나_없는_설명이면_NOT_FOUND() throws Exception {
    UUID stranger = member();
    UUID noteId = created(note(tenant, "n1", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}"));

    note(stranger, "s1", "{\"text\":\"남의 설명\",\"basis\":\"GUESS\"}")
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    respond(noteId, stranger, "s2", "ACK", null).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    respond(UUID.randomUUID(), landlord, "s3", "ACK", null)
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 공유를_그만둔_사람은_남기지도_답하지도_못한다() throws Exception {
    UUID noteId = created(note(landlord, "n1", "{\"text\":\"배관 노후\",\"basis\":\"GUESS\"}"));
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    note(tenant, "n2", "{\"text\":\"윗집 배관\",\"basis\":\"GUESS\"}")
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    respond(noteId, tenant, "r1", "ACK", null)
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    detail(tenant).andExpect(jsonPath("$.availableActions", not(hasItem("cause-notes"))));
  }

  // ---- 준비물 ----

  private ResultActions note(UUID memberId, String key, String json) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/cause-notes", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions respond(
      UUID noteId, UUID memberId, String key, String answer, String reason) throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return mvc.perform(
        post("/api/cause-notes/{id}/respond", noteId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions detail(UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)))
        .andExpect(status().isOk());
  }

  private static UUID created(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private DigestInput digest(
      UUID noteId, String text, String basis, String expertName, UUID previousId) {
    return new DigestInput(
        "cause_note",
        noteId,
        1,
        List.of(
            new DigestField("text", text),
            new DigestField("basis", basis),
            new DigestField("expert_name", expertName),
            new DigestField("previous_id", previousId == null ? null : previousId.toString())));
  }

  private Map<String, Object> noteRow(UUID noteId) {
    return jdbc.queryForMap("SELECT * FROM record.cause_note WHERE id = ?", noteId);
  }

  private Map<String, Object> actionOf(UUID noteId, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE cause_note_id = ? AND type = ?", noteId, type);
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE repair_case_id = ?", Long.class, caseId);
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register() throws Exception {
    return created(
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

  /** 기본 목록(V7)에서 복사된 "방 › 천장". */
  private UUID part() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '방' AND t.name = '천장'
        """,
        id,
        room);
    return id;
  }
}
