package com.chilsuitjo.back.timeline.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
 * 경과 보기({@code GET /api/rooms/{id}/timeline}, 06 REP-11a·API 표, 03 3-1 보이는 범위·6-6 9번). 응답 {@code
 * {mySide, asOf, items[]}}의 칸은 이 작업에서 정했다(06에 칸이 없음). 통지 상태(보낸 쪽만)는 알림 쪽 읽기 창구가 없어 이번 범위 밖(보류).
 * 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomTimelineApiTest {

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
  void REP_11a_경과는_서버_시각_순으로_보이고_행동_칸이_채워진다() throws Exception {
    UUID caseId = register(2);
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    supplement(caseId, tenant, "다시 찍었어요").andExpect(status().isOk());

    String body = body(timeline(landlord).andExpect(status().isOk()));

    assertThat((String) JsonPath.read(body, "$.mySide")).isEqualTo("LANDLORD");
    assertThat(JsonPath.<Object>read(body, "$.asOf")).isNull();
    List<String> types = JsonPath.read(body, "$.items[*].type");
    assertThat(types).containsExactly("REGISTER", "RESPOND_DEFECT", "SUPPLEMENT");
    List<String> sides = JsonPath.read(body, "$.items[*].side");
    assertThat(sides).containsExactly("TENANT", "LANDLORD", "TENANT");
    List<String> at = JsonPath.read(body, "$.items[*].at");
    assertThat(at).isSortedAccordingTo((a, b) -> Instant.parse(a).compareTo(Instant.parse(b)));
    List<String> ids = JsonPath.read(body, "$.items[*].id");
    assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> UUID.fromString(id));
    List<String> kinds = JsonPath.read(body, "$.items[*].target.kind");
    assertThat(kinds).containsOnly("REPAIR_CASE");
    List<String> targetIds = JsonPath.read(body, "$.items[*].target.id");
    assertThat(targetIds).containsOnly(caseId.toString());
    assertThat((String) JsonPath.read(body, "$.items[1].reason")).isEqualTo("사진이 흐려요");
    assertThat(JsonPath.<Object>read(body, "$.items[0].reason")).isNull();
    // 사진: 등록 행동에 2장, 지운 흔적 없음. payload에 사진 지문 키는 없다
    List<Map<String, Object>> photos = JsonPath.read(body, "$.items[0].photos");
    assertThat(photos).hasSize(2);
    for (Map<String, Object> photo : photos) {
      assertThat(UUID.fromString((String) photo.get("photoId"))).isNotNull();
      assertThat(photo).containsKey("erased");
      assertThat(photo.get("erased")).isNull();
    }
    Map<String, Object> payload = JsonPath.read(body, "$.items[0].payload");
    assertThat(payload).doesNotContainKey("photos");
    // 세입자가 봐도 같은 세 건
    String mine = body(timeline(tenant).andExpect(status().isOk()));
    assertThat((String) JsonPath.read(mine, "$.mySide")).isEqualTo("TENANT");
    List<String> mineTypes = JsonPath.read(mine, "$.items[*].type");
    assertThat(mineTypes).containsExactly("REGISTER", "RESPOND_DEFECT", "SUPPLEMENT");
  }

  @Test
  void REP_11a_경과에는_행동_번호와_해시와_서버_내부_값을_넣지_않는다() throws Exception {
    // 03 3-1: 행동 번호·해시는 화면·내려받기 어디에도 내보내지 않는다(번호 틈으로 숨긴 기록이 드러남)
    UUID caseId = register(1);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    ResultActions result = timeline(tenant).andExpect(status().isOk());
    for (int i = 0; i < 2; i++) {
      String item = "$.items[" + i + "].";
      for (String hidden :
          List.of(
              "roomSeq",
              "room_seq",
              "prevHash",
              "hash",
              "contentDigest",
              "actorKey",
              "actorMemberId",
              "idempotencyKey",
              "requestId",
              "seenVersionNo")) {
        result.andExpect(jsonPath(item + hidden).doesNotExist());
      }
    }
    result.andExpect(jsonPath("$.deletions").doesNotExist());
  }

  @Tag("invariant-18")
  @Test
  void REP_11a_상대가_공유를_그만둔_뒤_상대_행동은_안_보이고_기준_시각이_나온다() throws Exception {
    // 불변식 18 · 03 6-6 9번: 세입자가 그만둔 시각 뒤 집주인의 답은 세입자에게 안 보인다. 내 행동은 늘 보인다
    UUID caseId = register(1);
    Timestamp stoppedAt = createdAt(caseId);
    stopSharing(PartySide.TENANT, stoppedAt);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    String mine = body(timeline(tenant).andExpect(status().isOk()));
    List<String> tenantSees = JsonPath.read(mine, "$.items[*].type");
    assertThat(tenantSees).containsExactly("REGISTER");
    assertThat(Instant.parse(JsonPath.read(mine, "$.asOf"))).isEqualTo(stoppedAt.toInstant());

    String theirs = body(timeline(landlord).andExpect(status().isOk()));
    List<String> landlordSees = JsonPath.read(theirs, "$.items[*].type");
    assertThat(landlordSees).containsExactly("REGISTER", "RESPOND_DEFECT");
    assertThat(Instant.parse(JsonPath.read(theirs, "$.asOf"))).isEqualTo(stoppedAt.toInstant());
  }

  @Tag("invariant-18")
  @Test
  void REP_11a_기준_측이_공유를_그만둔_뒤_상대가_보낸_확인_요청으로_작성_중_항목이_드러나지_않는다() throws Exception {
    // #352 · 06 INSP-1f · 불변식 18: 점검 상세(InspectionSight)와 같게 그만둔 시각에 작성 중이던 항목은 보이지 않는다
    draftHiddenThenCounterpartRequests();

    String theirs = body(timeline(landlord).andExpect(status().isOk()));
    List<String> landlordSees = JsonPath.read(theirs, "$.items[*].type");
    assertThat(landlordSees).containsExactly("START_INSPECTION", "REQUEST_INSPECTION_CONFIRM");

    String mine = body(timeline(tenant).andExpect(status().isOk()));
    List<String> tenantSees = JsonPath.read(mine, "$.items[*].type");
    assertThat(tenantSees).containsExactly("START_INSPECTION", "ADD_ITEM");
  }

  @Test
  void REP_11a_내가_공유를_그만둔_뒤에도_상대_행동은_안_보인다_양방향() throws Exception {
    // 집주인이 그만두면 그 뒤 세입자의 보충은 집주인에게 안 보이고, 세입자는 자기 보충을 본다
    UUID caseId = register(1);
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);
    supplement(caseId, tenant, "다시 찍었어요").andExpect(status().isOk());

    String mine = body(timeline(landlord).andExpect(status().isOk()));
    List<String> landlordSees = JsonPath.read(mine, "$.items[*].type");
    assertThat(landlordSees).containsExactly("REGISTER", "RESPOND_DEFECT");
    assertThat((String) JsonPath.read(mine, "$.asOf")).isNotBlank();

    String theirs = body(timeline(tenant).andExpect(status().isOk()));
    List<String> tenantSees = JsonPath.read(theirs, "$.items[*].type");
    assertThat(tenantSees).containsExactly("REGISTER", "RESPOND_DEFECT", "SUPPLEMENT");
    assertThat((String) JsonPath.read(theirs, "$.asOf")).isNotBlank();
  }

  // 거주 종료 행동이 공유 중단 뒤에도 양쪽에 보이는 예외(불변식 18)는 거주 종료 API(ROOM-9)가 아직 없어
  // ActionVisibilityTest(단위)로 본다. API가 생기면 여기에 한 줄 더한다.

  @Tag("invariant-18")
  @Test
  void REP_11a_상대가_탈퇴하면_기준_시각은_null이고_중단_동안_탈퇴자_행동은_계속_안_보인다() throws Exception {
    // 03 6-4·CONS-3q: 제한은 풀리지만 중단 동안 탈퇴자가 남긴 행동은 보이지 않는다
    UUID caseId = register(1);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    stopSharing(PartySide.LANDLORD, createdAt(caseId));
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    String mine = body(timeline(tenant).andExpect(status().isOk()));
    List<String> types = JsonPath.read(mine, "$.items[*].type");
    assertThat(types).containsExactly("REGISTER");
    assertThat(JsonPath.<Object>read(mine, "$.asOf")).isNull();
  }

  @Test
  void REP_11a_고친_내용은_원래_기록과_같은_target으로_나온다() throws Exception {
    // 06 REP-11a "원래 기록 옆에 이력": 확인 전 고치기는 바로 적용되고 행동 CORRECT가 수리 건을 가리킨다
    UUID caseId = register(1);
    mvc.perform(
            post("/api/corrections")
                .with(loginAs(tenant))
                .header("Idempotency-Key", "cor-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
                        + caseId
                        + "\",\"changes\":[{\"field\":\"title\",\"newValue\":\"천장 누수 심해짐\"}]}"))
        .andExpect(status().isOk());

    String body = body(timeline(landlord).andExpect(status().isOk()));

    List<String> types = JsonPath.read(body, "$.items[*].type");
    assertThat(types).containsExactly("REGISTER", "CORRECT");
    List<String> kinds = JsonPath.read(body, "$.items[*].target.kind");
    assertThat(kinds).containsOnly("REPAIR_CASE");
    List<String> targetIds = JsonPath.read(body, "$.items[*].target.id");
    assertThat(targetIds).containsOnly(caseId.toString());
  }

  @Test
  void REP_11a_없는_호실이나_그_호실_회원이_아니면_NOT_FOUND() throws Exception {
    register(1);

    mvc.perform(get("/api/rooms/{id}/timeline", UUID.randomUUID()).with(loginAs(tenant)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    timeline(member())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- 준비물 ----

  private ResultActions timeline(UUID memberId) throws Exception {
    return mvc.perform(get("/api/rooms/{id}/timeline", room).with(loginAs(memberId)));
  }

  private Timestamp createdAt(UUID caseId) {
    return jdbc.queryForObject(
        "SELECT created_at FROM state.repair_case WHERE id = ?", Timestamp.class, caseId);
  }

  private void stopSharing(PartySide side, Timestamp at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        at,
        room,
        side.name());
  }

  /**
   * #352 재현: 세입자(기준 측)가 퇴거 점검을 시작해 사진 1장 항목을 더하고 공유를 그만둔 뒤, 7일이 지나 집주인이 직접 확인 요청을 보낸다(06 INSP-1f).
   * 시작 시각은 점검 행만 옮겨 작성 중 기간을 지난 것으로 한다. 행동 기록 시각은 그대로라 항목은 공유 중단 전, 확인 요청은 뒤다.
   */
  private void draftHiddenThenCounterpartRequests() throws Exception {
    String started =
        mvc.perform(
                post("/api/rooms/{id}/inspections", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "start-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"MOVE_OUT\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID inspection = UUID.fromString(started.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    mvc.perform(
            post("/api/inspections/{id}/items", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "item-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"roomPartId\":\""
                        + part
                        + "\",\"description\":\"벽지 찢어짐\",\"uploadTickets\":[\""
                        + UUID.randomUUID()
                        + "\"]}"))
        .andExpect(status().isCreated());
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        Timestamp.from(Instant.now()),
        room);
    jdbc.update(
        "UPDATE state.inspection SET created_at = created_at - INTERVAL '8 days' WHERE id = ?",
        inspection);
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "req-" + UUID.randomUUID()))
        .andExpect(status().isOk());
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  /** 세입자가 사진 {@code photos}장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register(int photos) throws Exception {
    StringBuilder tickets = new StringBuilder();
    for (int i = 0; i < photos; i++) {
      tickets.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    String body =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\","
                            + "\"uploadTickets\":["
                            + tickets
                            + "]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions respond(UUID caseId, UUID memberId, String answer, String reason)
      throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":1}";
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "res-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions supplement(UUID caseId, UUID memberId, String description)
      throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/supplement", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "sup-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"" + description + "\"}"));
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
