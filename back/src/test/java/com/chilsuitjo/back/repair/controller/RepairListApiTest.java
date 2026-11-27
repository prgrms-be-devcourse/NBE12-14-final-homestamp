package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import java.sql.Timestamp;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 호실의 수리 건 목록({@code GET /api/rooms/{id}/repair-cases}, 06 4-6, #283)을 보안·진짜 DB까지 띄워 본다. 줄의 값과 보이는
 * 범위가 상세와 같은지 함께 본다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairListApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

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

  @Test
  void 새것부터_상세와_같은_칸으로_보인다() throws Exception {
    UUID older = register(tenant, "천장 누수");
    UUID newer = register(tenant, "벽지 곰팡이");
    respond(older, landlord, "ACK").andExpect(status().isOk());

    list(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.mySide").value("TENANT"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
        .andExpect(jsonPath("$.items[1].id").value(older.toString()))
        .andExpect(jsonPath("$.items[1].registeredBy").value("TENANT"))
        .andExpect(jsonPath("$.items[1].createdAt").value(createdAt(older)))
        .andExpect(jsonPath("$.items[1].content.no").value(1))
        .andExpect(jsonPath("$.items[1].content.partName").value("천장"))
        .andExpect(jsonPath("$.items[1].content.title").value("천장 누수"))
        .andExpect(jsonPath("$.items[1].content.description").doesNotExist())
        .andExpect(jsonPath("$.items[1].status").value("NOT_STARTED"))
        .andExpect(jsonPath("$.items[1].closeRequestedBy").doesNotExist())
        .andExpect(jsonPath("$.items[1].closedBy").doesNotExist())
        .andExpect(jsonPath("$.items[1].defect.ack").value("ACK"))
        .andExpect(jsonPath("$.items[1].money.status").value("UNDECIDED"))
        .andExpect(jsonPath("$.items[1].waiting").isEmpty())
        .andExpect(jsonPath("$.items[1].availableActions").doesNotExist())
        // 마지막 당사자 행동은 집주인의 [맞아요]
        .andExpect(jsonPath("$.items[1].lastActionAt").value(lastCaseActionAt(older)))
        .andExpect(jsonPath("$.items[0].lastActionAt").value(createdAt(newer)));
  }

  @Test
  void 종료_확인_대기면_요청한_측이_보인다() throws Exception {
    UUID caseId = register(tenant, "천장 누수");
    action(caseId, tenant, "start").andExpect(status().isOk());
    action(caseId, landlord, "request-close").andExpect(status().isOk());

    list(tenant)
        .andExpect(jsonPath("$.items[0].status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.items[0].closeRequestedBy").value("LANDLORD"))
        .andExpect(jsonPath("$.items[0].lastActionAt").value(lastCaseActionAt(caseId)));
  }

  @Test
  void 진행_상황과_원인_설명도_이_건의_행동으로_센다() throws Exception {
    UUID caseId = register(tenant, "천장 누수");
    mvc.perform(
            withKey(post("/api/repair-cases/{id}/progress-notes", caseId), tenant)
                .content("{\"text\":\"업체에 연락했어요\"}"))
        .andExpect(status().isCreated());
    list(landlord).andExpect(jsonPath("$.items[0].lastActionAt").value(lastCaseActionAt(caseId)));

    mvc.perform(
            withKey(post("/api/repair-cases/{id}/cause-notes", caseId), landlord)
                .content("{\"text\":\"배관 노후 같아요\",\"basis\":\"GUESS\"}"))
        .andExpect(status().isCreated());
    String causeAt =
        jdbc.queryForObject(
                "SELECT max(a.created_at) FROM record.action a JOIN record.cause_note n"
                    + " ON a.cause_note_id = n.id WHERE n.repair_case_id = ?",
                Timestamp.class,
                caseId)
            .toInstant()
            .toString();
    list(tenant).andExpect(jsonPath("$.items[0].lastActionAt").value(causeAt));
  }

  @Test
  void 고친_내용에_답해도_이_건의_행동으로_센다() throws Exception {
    // 같이 확인한 뒤 고치면 확인 요청이 가고, 응답 행동의 대상은 고친 내용이다(REP-7c·7e)
    UUID caseId = register(tenant, "천장 누수");
    respond(caseId, landlord, "ACK").andExpect(status().isOk());
    String body =
        mvc.perform(
                withKey(post("/api/corrections"), tenant)
                    .content(
                        "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
                            + caseId
                            + "\",\"changes\":[{\"field\":\"title\",\"newValue\":\"천장 물샘\"}],"
                            + "\"reason\":\"오타\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID correctionId =
        UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));
    mvc.perform(
            withKey(post("/api/corrections/{id}/respond", correctionId), landlord)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());

    String answeredAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE correction_request_id = ?"
                    + " AND type = 'RESPOND_CORRECTION'",
                Timestamp.class,
                correctionId)
            .toInstant()
            .toString();
    list(tenant)
        .andExpect(jsonPath("$.items[0].lastActionAt").value(answeredAt))
        .andExpect(jsonPath("$.items[0].content.title").value("천장 물샘"));
  }

  @Test
  void 커서로_다음_쪽을_읽고_마지막_쪽에는_다음_커서가_없다() throws Exception {
    UUID first = register(tenant, "하나");
    UUID second = register(tenant, "둘");
    UUID third = register(tenant, "셋");

    list(tenant, null, "2")
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(third.toString()))
        .andExpect(jsonPath("$.items[1].id").value(second.toString()))
        .andExpect(jsonPath("$.nextCursor").value(second.toString()));
    list(tenant, second.toString(), "2")
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(first.toString()))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void 틀린_커서와_크기는_VALIDATION_FAILED() throws Exception {
    register(tenant, "천장 누수");
    UUID otherRoomCase = UUID.randomUUID();

    for (String[] query :
        new String[][] {
          {null, "0"}, {null, "101"}, {"not-a-uuid", "20"}, {otherRoomCase.toString(), "20"}
        }) {
      list(tenant, query[0], query[1])
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Test
  void 남의_호실은_NOT_FOUND() throws Exception {
    register(tenant, "천장 누수");

    list(member())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/rooms/{id}/repair-cases", UUID.randomUUID()).with(loginAs(tenant)))
        .andExpect(status().isNotFound());
  }

  @Test
  void 공유를_그만둔_뒤에는_그_시각_기준으로_고정되고_뒤에_상대가_등록한_건은_빠진다() throws Exception {
    // 중단 전 수리 건 → 세입자가 공유를 그만둠 → 집주인이 답하고 새 하자를 등록(03 6-6 9번, 불변식 18)
    UUID before = register(tenant, "천장 누수");
    Timestamp stoppedAt =
        jdbc.queryForObject(
            "SELECT created_at FROM state.repair_case WHERE id = ?", Timestamp.class, before);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        stoppedAt,
        room);
    respond(before, landlord, "ACK").andExpect(status().isOk());
    UUID after = register(landlord, "보일러 고장");

    list(tenant)
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(before.toString()))
        .andExpect(jsonPath("$.items[0].defect.ack").value("PENDING"))
        // 집주인의 [맞아요]는 보이지 않아 "N일째"도 다시 시작되지 않는다
        .andExpect(jsonPath("$.items[0].lastActionAt").value(createdAt(before)));
    // 남은 쪽도 같은 시각으로 고정되지만 자기 기록과 행동은 보인다
    list(landlord)
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(after.toString()))
        .andExpect(jsonPath("$.items[1].defect.ack").value("ACK"))
        .andExpect(jsonPath("$.items[1].lastActionAt").value(lastCaseActionAt(before)));
  }

  @Test
  void 꺼_둔_설비의_건도_보인다() throws Exception {
    UUID caseId = register(tenant, "천장 누수");
    jdbc.update("UPDATE state.room_part SET enabled = false WHERE id = ?", part);

    list(tenant).andExpect(jsonPath("$.items[0].id").value(caseId.toString()));
  }

  @Test
  void 로그인하지_않으면_LOGIN_REQUIRED() throws Exception {
    mvc.perform(get("/api/rooms/{id}/repair-cases", room))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  // ---- #289 리뷰: 상세와 같은 보이는 범위 ----

  @Test
  void 도장_전에_고친_수리_건도_공유_중단_뒤_목록에_보인다() throws Exception {
    // 도장 전 고치기는 수리 건 대상 CORRECT로 남고 바로 새 판이 된다. 다시 쌓을 때 INVALID_STATE가 나지 않아야 한다(#287)
    UUID caseId = register(tenant, "천장 누수");
    correct(caseId, tenant, "천장 물샘").andExpect(status().is2xxSuccessful());
    stopSharing(PartySide.TENANT);

    list(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].content.no").value(2))
        .andExpect(jsonPath("$.items[0].content.title").value("천장 물샘"));
  }

  @Test
  void 공유_중단_전에_맞아요한_고친_내용의_판_번호가_상세와_같다() throws Exception {
    // 고친 내용 맞아요(대상이 고친 내용)가 새 판을 만든다. 빼고 쌓으면 판 번호가 뒤처진다(cheeerry0 1번)
    UUID caseId = register(tenant, "천장 누수");
    respond(caseId, landlord, "ACK").andExpect(status().isOk());
    UUID correctionId = correctionIdOf(correct(caseId, tenant, "천장 물샘"));
    mvc.perform(
            withKey(post("/api/corrections/{id}/respond", correctionId), landlord)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());
    stopSharing(PartySide.TENANT);

    list(tenant)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.items[0].content.no").value(2))
        .andExpect(jsonPath("$.items[0].content.title").value("천장 물샘"));
    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(jsonPath("$.content.no").value(2));
  }

  @Test
  void 공유_중단_전에_합의된_부담은_목록에도_그대로_보인다() throws Exception {
    // 부담 제안 행동은 대상이 제안이라 돈 안건 장치가 다시 계산한다(ProposalService.statusAsSeen, 상세와 같음)
    UUID agreed = register(tenant, "천장 누수");
    UUID proposal = proposalIdOf(propose(agreed, tenant, "{\"lines\":[],\"noCost\":true}"));
    mvc.perform(withKey(post("/api/proposals/{id}/agree", proposal), landlord))
        .andExpect(status().isOk());
    stopSharing(PartySide.TENANT);

    list(tenant)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.items[0].money.status").value("NO_COST"));
  }

  @Test
  void 보이지_않는_수리_건을_커서로_주면_틀린_커서와_같다() throws Exception {
    // 공유를 그만둔 뒤 상대가 등록한 건은 상세가 404라 커서로도 쓸 수 없다(cheeerry0 2번)
    register(tenant, "천장 누수");
    stopSharing(PartySide.TENANT);
    UUID hidden = register(landlord, "보일러 고장");

    list(tenant, hidden.toString(), null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 등록한 쪽에게는 보이는 건이라 커서로 쓸 수 있다
    list(landlord, hidden.toString(), null).andExpect(status().isOk());
  }

  @Test
  void 공유를_그만둔_동안_상대가_시작하고_탈퇴하면_asOf는_없고_그_행동은_멈춘_기간에_세지_않는다() throws Exception {
    // 상세와 같은 탈퇴 기준(decisions 2026-11-27): 상태는 지금 값, 탈퇴자가 중단 동안 남긴 행동은 보이지 않는다(#294)
    UUID caseId = register(tenant, "천장 누수");
    stopSharing(PartySide.TENANT);
    action(caseId, landlord, "start").andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    list(tenant)
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.items[0].lastActionAt").value(createdAt(caseId)));
  }

  // ---- 준비물 ----

  /** 이 측이 지금 공유를 그만둔다. 앞에서 남긴 기록은 모두 이 시각 전이다. */
  private void stopSharing(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = clock_timestamp()"
            + " WHERE room_record_id = ? AND side = ?",
        room,
        side.name());
  }

  private ResultActions correct(UUID caseId, UUID who, String title) throws Exception {
    return mvc.perform(
        withKey(post("/api/corrections"), who)
            .content(
                "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
                    + caseId
                    + "\",\"changes\":[{\"field\":\"title\",\"newValue\":\""
                    + title
                    + "\"}],\"reason\":\"오타\"}"));
  }

  private static UUID correctionIdOf(ResultActions result) throws Exception {
    String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));
  }

  private ResultActions propose(UUID caseId, UUID who, String body) throws Exception {
    return mvc.perform(
        withKey(post("/api/repair-cases/{id}/proposals", caseId), who).content(body));
  }

  private static UUID proposalIdOf(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions list(UUID memberId) throws Exception {
    return list(memberId, null, null);
  }

  private ResultActions list(UUID memberId, String cursor, String size) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/rooms/{id}/repair-cases", room).with(loginAs(memberId));
    if (cursor != null) {
      request.param("cursor", cursor);
    }
    if (size != null) {
      request.param("size", size);
    }
    return mvc.perform(request);
  }

  private String createdAt(UUID caseId) {
    return jdbc.queryForObject(
            "SELECT created_at FROM state.repair_case WHERE id = ?", Timestamp.class, caseId)
        .toInstant()
        .toString();
  }

  private String lastCaseActionAt(UUID caseId) {
    return jdbc.queryForObject(
            "SELECT max(created_at) FROM record.action WHERE repair_case_id = ?",
            Timestamp.class,
            caseId)
        .toInstant()
        .toString();
  }

  private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, UUID who) {
    return request
        .with(loginAs(who))
        .header("Idempotency-Key", "key-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON);
  }

  /** 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register(UUID who, String title) throws Exception {
    String body =
        mvc.perform(
                withKey(post("/api/rooms/{id}/repair-cases", room), who)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\""
                            + title
                            + "\",\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions respond(UUID caseId, UUID who, String answer) throws Exception {
    return mvc.perform(
        withKey(post("/api/repair-cases/{id}/respond", caseId), who)
            .content("{\"answer\":\"" + answer + "\",\"seenVersionNo\":1}"));
  }

  private ResultActions action(UUID caseId, UUID who, String name) throws Exception {
    return mvc.perform(withKey(post("/api/repair-cases/{id}/" + name, caseId), who).content("{}"));
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
