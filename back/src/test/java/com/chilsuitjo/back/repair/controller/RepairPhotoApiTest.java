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
import com.chilsuitjo.back.notification.dto.InfoCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.service.FakeInAppNotifier;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
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
 * 06 REP-7g "덧붙이기는 도장이 필요 없다"와 4-6 {@code POST /api/repair-cases/{id}/photos}(사진 추가)를
 * 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#377). 요청은 JSON 문자열로 보낸다. 사진은 가짜 사진 창구가 받는다.
 *
 * <p>근거: 06 4-6 해당 줄(요청 {@code {uploadTickets, stage}}·{@code Idempotency-Key}·응답 {@code
 * {photoIds}}·{@code ADD_PHOTO}·오류 코드), 4-5 상세 {@code defect.ackAt}, decisions 2026-11-27 마지막 줄.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairPhotoApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeInAppNotifier notifier;
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

  // ---- 성공 ----

  @Test
  void REP_7g_같이_확인한_건에_사진을_더해도_판_번호와_하자_인정과_상태가_그대로다() throws Exception {
    UUID caseId = acked();

    JsonNode res = body(add(caseId, tenant, "a1", tickets(1), "AFTER").andExpect(status().isOk()));

    assertThat(res.get("photoIds")).hasSize(1);
    String photoId = res.get("photoIds").get(0).asText();
    assertThat(countActions(caseId, "ADD_PHOTO")).isEqualTo(1);
    assertThat(action(caseId, "ADD_PHOTO").get("payload").toString()).contains(photoId);
    assertThat(action(caseId, "ADD_PHOTO").get("actor_side")).isEqualTo("TENANT");

    JsonNode detail = detail(caseId, tenant);
    assertThat(detail.at("/content/no").asInt()).isEqualTo(1);
    assertThat(detail.at("/defect/ack").asText()).isEqualTo("ACK");
    assertThat(detail.get("status").asText()).isEqualTo("NOT_STARTED");
    assertThat(photoOf(detail, photoId).get("stage").asText()).isEqualTo("AFTER");
    assertThat(caseRow(caseId))
        .containsEntry("defect_ack", "ACK")
        .containsEntry("status", "NOT_STARTED");
  }

  @Test
  void REP_7g_맞아요_뒤_더한_사진은_createdAt이_defect_ackAt보다_뒤다() throws Exception {
    UUID caseId = register();
    assertThat(detail(caseId, tenant).at("/defect/ackAt").isNull()).isTrue();
    respond(caseId, landlord, "r1", "ACK");
    String photoId =
        body(add(caseId, tenant, "a1", tickets(1), "BEFORE").andExpect(status().isOk()))
            .get("photoIds")
            .get(0)
            .asText();

    JsonNode detail = detail(caseId, tenant);
    Instant ackAt = Instant.parse(detail.at("/defect/ackAt").asText());
    Instant createdAt = Instant.parse(photoOf(detail, photoId).get("createdAt").asText());
    assertThat(createdAt).isAfter(ackAt);
  }

  @Test
  void REP_7g_여러_장을_더하면_photoIds가_업로드_표_순서이고_상세에_모두_보인다() throws Exception {
    UUID caseId = acked();

    JsonNode res = body(add(caseId, tenant, "a1", tickets(3), "BEFORE").andExpect(status().isOk()));

    assertThat(res.get("photoIds")).hasSize(3);
    List<String> ids = new ArrayList<>();
    detail(caseId, tenant).get("photos").forEach(p -> ids.add(p.get("id").asText()));
    List<String> added = new ArrayList<>();
    res.get("photoIds").forEach(p -> added.add(p.asText()));
    assertThat(ids.subList(ids.size() - 3, ids.size())).containsExactlyElementsOf(added);
  }

  @Test
  void REP_7g_같은_키로_다시_보내면_같은_photoIds와_Replayed_헤더이고_더_생기지_않는다() throws Exception {
    UUID caseId = acked();
    String tickets = tickets(2);
    JsonNode first =
        body(add(caseId, tenant, "same", tickets, "BEFORE").andExpect(status().isOk()));
    int photosBefore = detail(caseId, tenant).get("photos").size();

    ResultActions again =
        add(caseId, tenant, "same", tickets, "BEFORE")
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"));

    assertThat(body(again).get("photoIds")).isEqualTo(first.get("photoIds"));
    assertThat(countActions(caseId, "ADD_PHOTO")).isEqualTo(1);
    assertThat(detail(caseId, tenant).get("photos")).hasSize(photosBefore);
  }

  @Test
  void REP_7g_상대가_연결된_회원이면_서비스_안_알림이_하나_가고_확인_요청은_새로_열리지_않는다() throws Exception {
    UUID caseId = acked();
    int sentBefore = notifier.sent().size();
    var statusBefore =
        waits.statusOf(
            RequestTarget.of(TargetKind.REPAIR_CASE, caseId), RequestKind.DEFECT_CONFIRM);

    add(caseId, tenant, "a1", tickets(1), "BEFORE").andExpect(status().isOk());

    assertThat(notifier.sent()).hasSize(sentBefore + 1);
    List<InfoCommand> toLandlord = sentTo(landlord);
    assertThat(toLandlord).hasSize(1);
    assertThat(toLandlord.getFirst().roomRecordId()).isEqualTo(room);
    assertThat(toLandlord.getFirst().target())
        .isEqualTo(RequestTarget.of(TargetKind.REPAIR_CASE, caseId));
    assertThat(
            waits.statusOf(
                RequestTarget.of(TargetKind.REPAIR_CASE, caseId), RequestKind.DEFECT_CONFIRM))
        .isEqualTo(statusBefore);
  }

  // ---- 입력 거부 ----

  @Test
  void REP_7g_업로드_표가_0개_11개_빈_문자열이면_VALIDATION_FAILED이고_아무것도_남지_않는다() throws Exception {
    UUID caseId = acked();
    int photos = detail(caseId, tenant).get("photos").size();

    addRaw(caseId, tenant, "v1", "{\"uploadTickets\":[],\"stage\":\"BEFORE\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    add(caseId, tenant, "v2", tickets(11), "BEFORE")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    addRaw(caseId, tenant, "v3", "{\"uploadTickets\":[\"\"],\"stage\":\"BEFORE\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 업로드 표 칸이 아예 없어도 0개와 같다
    addRaw(caseId, tenant, "v4", "{\"stage\":\"BEFORE\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
    assertThat(detail(caseId, tenant).get("photos")).hasSize(photos);
  }

  @Test
  void REP_7g_단계가_없거나_BEFORE_AFTER가_아니면_VALIDATION_FAILED() throws Exception {
    UUID caseId = acked();
    String t = tickets(1);

    addRaw(caseId, tenant, "s1", "{\"uploadTickets\":" + t + "}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    addRaw(caseId, tenant, "s2", "{\"uploadTickets\":" + t + ",\"stage\":\"MOVE_IN\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
  }

  // ---- 호실·공유 상태 ----

  @Test
  void REP_7g_거주가_끝난_뒤에는_ROOM_ENDED() throws Exception {
    UUID caseId = acked();
    assertThat(detail(caseId, tenant).get("availableActions").toString()).contains("\"photos\"");
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    add(caseId, tenant, "a1", tickets(1), "BEFORE")
        .andExpect(jsonPath("$.code").value("ROOM_ENDED"));
    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
    // 버튼도 실제 요청과 같은 판단이라 거주 종료 뒤에는 없다(06 4-0)
    assertThat(detail(caseId, tenant).get("availableActions").toString())
        .doesNotContain("\"photos\"");
  }

  @Test
  void REP_7g_정산이_끝난_뒤에는_SETTLED() throws Exception {
    UUID caseId = acked();
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    add(caseId, tenant, "a1", tickets(1), "BEFORE").andExpect(jsonPath("$.code").value("SETTLED"));
    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
  }

  @Test
  void REP_7g_공유를_그만둔_사람은_그만두기_전_수리_건에_SHARING_STOPPED() throws Exception {
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at + interval '1 second' FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        caseId,
        room);

    add(caseId, tenant, "a1", tickets(1), "BEFORE")
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
    assertThat(detail(caseId, tenant).get("availableActions").toString())
        .doesNotContain("\"photos\"");
  }

  @Test
  void REP_7g_공유를_그만둔_사람도_그만둔_동안_자기가_등록한_새_수리_건에는_더할_수_있다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - interval '1 hour'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID caseId = register();
    assertThat(detail(caseId, tenant).get("availableActions").toString()).contains("\"photos\"");

    add(caseId, tenant, "a1", tickets(1), "BEFORE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoIds.length()").value(1));
    assertThat(countActions(caseId, "ADD_PHOTO")).isEqualTo(1);
  }

  @Test
  void REP_7g_남의_호실이거나_없는_수리_건이면_NOT_FOUND() throws Exception {
    UUID caseId = acked();

    add(caseId, member(), "a1", tickets(1), "BEFORE")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    add(UUID.randomUUID(), tenant, "a2", tickets(1), "BEFORE")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(countActions(caseId, "ADD_PHOTO")).isZero();
  }

  // ---- 준비물 ----

  private ResultActions add(UUID caseId, UUID memberId, String key, String tickets, String stage)
      throws Exception {
    return addRaw(
        caseId, memberId, key, "{\"uploadTickets\":" + tickets + ",\"stage\":\"" + stage + "\"}");
  }

  private ResultActions addRaw(UUID caseId, UUID memberId, String key, String json)
      throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/photos", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /** 새 업로드 표 n개를 담은 JSON 배열. */
  private static String tickets(int n) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      sb.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    return sb.append("]").toString();
  }

  private JsonNode detail(UUID caseId, UUID memberId) throws Exception {
    return body(
        mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)))
            .andExpect(status().isOk()));
  }

  private static JsonNode photoOf(JsonNode detail, String photoId) {
    for (JsonNode p : detail.get("photos")) {
      if (p.get("id").asText().equals(photoId)) {
        return p;
      }
    }
    throw new AssertionError("상세 photos에 " + photoId + " 가 없다");
  }

  private static JsonNode body(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString());
  }

  /** {@code FakeInAppNotifier.sent()} 중 이 회원이 받은 것(기록의 {@code recipientMemberId}). */
  private List<InfoCommand> sentTo(UUID memberId) {
    return notifier.sent().stream().filter(s -> memberId.equals(s.recipientMemberId())).toList();
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register() throws Exception {
    String res =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\","
                            + "\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 등록하고 집주인이 맞아요까지 누른 수리 건(같이 확인한 건). */
  private UUID acked() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r-" + UUID.randomUUID(), "ACK");
    return caseId;
  }

  private void respond(UUID caseId, UUID memberId, String key, String answer) throws Exception {
    mvc.perform(
            post("/api/repair-cases/{id}/respond", caseId)
                .with(loginAs(memberId))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"" + answer + "\",\"seenVersionNo\":1}"))
        .andExpect(status().isOk());
  }

  private Map<String, Object> caseRow(UUID caseId) {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  private Map<String, Object> action(UUID caseId, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE repair_case_id = ? AND type = ?", caseId, type);
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
