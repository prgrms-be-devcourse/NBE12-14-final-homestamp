package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.PartySide;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 06 REP-1 하자 등록을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 사진·알리기는 아직 없다(#134).
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다. 잠금(
 * {@code JpaRoomGateReader})·부위({@code JpaRoomQueryService})·자리 상태({@code JpaCounterpartReader})가
 * 모두 진짜라 {@code room_record}·{@code party}·{@code room_part} 행으로 정한다(#143, #140).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairRegisterApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static final Instant STOPPED = Instant.parse("2026-11-01T00:00:00Z");

  private UUID room;
  private UUID tenant;
  private UUID part;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room();
    tenant = member();
    part = part(room, false);
    // 잠금·당사자 확인은 진짜(JpaRoomGateReader)라 party 행으로 정한다: 세입자(나)와 집주인 모두 연결됨
    seat(PartySide.TENANT, tenant, "CONNECTED");
    seat(PartySide.LANDLORD, member(), "CONNECTED");
  }

  @Test
  void 등록하면_수리_건과_판_1과_행동이_함께_생긴다() throws Exception {
    String id =
        register("k1", body(part, "천장 누수", "비 오면 물이 떨어져요"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(RepairController.REPLAYED))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    UUID caseId = UUID.fromString(id);

    Map<String, Object> head =
        jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
    assertThat(head)
        .containsEntry("room_record_id", room)
        .containsEntry("status", "NOT_STARTED")
        .containsEntry("defect_ack", "PENDING")
        .containsEntry("money_status", "UNDECIDED")
        .containsEntry("created_by_side", "TENANT");

    Map<String, Object> content =
        jdbc.queryForMap("SELECT * FROM record.repair_content WHERE repair_case_id = ?", caseId);
    assertThat(content)
        .containsEntry("id", head.get("current_content_id"))
        .containsEntry("no", 1)
        .containsEntry("room_part_id", part)
        .containsEntry("title", "천장 누수")
        .containsEntry("description", "비 오면 물이 떨어져요");

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE repair_case_id = ?", caseId);
    assertThat(action)
        .containsEntry("type", "REGISTER")
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT")
        .containsEntry("room_seq", 1L);
    assertThat(action.get("payload")).isNull();
    // 새 판을 만드는 행동이라 그 판 #1을 지문으로 남긴다(안내서 3-3). 칸 순서도 형식의 일부다.
    DigestInput expected =
        new DigestInput(
            "repair_content",
            (UUID) content.get("id"),
            1,
            List.of(
                new DigestField("room_part_id", part.toString()),
                new DigestField("title", "천장 누수"),
                new DigestField("description", "비 오면 물이 떨어져요")));
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(expected));
    // 상대가 연결돼 있어 바로 보인다. 시각은 행동 시각과 같다(decisions 2026-11-18, #94 at()).
    assertThat(head.get("first_shown_at")).isEqualTo(action.get("created_at"));
    assertThat(head.get("created_at")).isEqualTo(action.get("created_at"));
  }

  @Test
  void 같은_키로_다시_보내면_새로_만들지_않고_처음_수리_건을_돌려준다() throws Exception {
    String first =
        register("same", body(part, "곰팡이", null))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    register("same", body(part, "곰팡이", null))
        .andExpect(status().isCreated())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")));

    assertThat(count("state.repair_case")).isEqualTo(1);
    assertThat(count("record.repair_content")).isEqualTo(1);
  }

  @Test
  void 설명은_없어도_등록된다() throws Exception {
    register("no-desc", body(part, "문고리 고장", null)).andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM record.repair_content WHERE room_record_id = ?",
                String.class,
                room))
        .isNull();
  }

  @Test
  void 제목은_앞뒤_공백을_자르고_공백뿐인_설명은_없는_것으로_남긴다() throws Exception {
    register("trim", body(part, "  천장 누수  ", " \\n\\t ")).andExpect(status().isCreated());

    Map<String, Object> content =
        jdbc.queryForMap(
            "SELECT title, description FROM record.repair_content WHERE room_record_id = ?", room);
    assertThat(content.get("title")).isEqualTo("천장 누수");
    assertThat(content.get("description")).isNull();
  }

  @Test
  void 설명은_2000자까지_받는다() throws Exception {
    register("desc-2000", body(part, "누수", "가".repeat(2000))).andExpect(status().isCreated());
    register("desc-2001", body(part, "누수", "가".repeat(2001)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(count("state.repair_case")).isEqualTo(1);
  }

  @Test
  void 제목이나_설명에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    register("rrn-title", body(part, "900101-1234567 누수", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    register("rrn-desc", body(part, "누수", "집주인 9001011234567"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 아직_받지_않는_사진_칸이_오면_VALIDATION_FAILED() throws Exception {
    register(
            "tickets",
            "{\"roomPartId\":\"" + part + "\",\"title\":\"누수\",\"uploadTickets\":[\"t1\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(count("state.repair_case")).isZero();
  }

  @Test
  void 상대가_아직_연결되지_않았으면_처음_보인_시각은_비어_있다() throws Exception {
    changeParty(PartySide.LANDLORD, null, "INVITED", null);

    register("invited", body(part, "창문 틈", null)).andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "SELECT first_shown_at FROM state.repair_case WHERE room_record_id = ?",
                Timestamp.class,
                room))
        .isNull();
  }

  @Test
  void 내가_공유를_그만뒀어도_등록은_되지만_상대에게_보이지_않는다() throws Exception {
    // 공유를 그만둔 사람도 나만 보는 기록으로 남길 수 있다(ActionType WHILE_SHARING_STOPPED, 03 B)
    changeParty(PartySide.TENANT, tenant, "SHARING_STOPPED", STOPPED);

    register("my-stop", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 상대가_공유를_그만뒀으면_처음_보인_시각은_비어_있다() throws Exception {
    changeParty(PartySide.LANDLORD, member(), "SHARING_STOPPED", STOPPED);

    register("their-stop", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 상대가_탈퇴했으면_처음_보인_시각은_비어_있다() throws Exception {
    // 탈퇴하면 공유 중단 시각이 남아 있어도 보지 않는다(06 CONS-3q). 연결이 아니라 보이지 않는다
    changeParty(PartySide.LANDLORD, member(), "WITHDRAWN", STOPPED);

    register("withdrawn", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 정산이_끝난_호실에는_SETTLED() throws Exception {
    // 정산 완료면 거주 종료 시각도 있다(V1 CHECK). 정산 완료가 거주 종료보다 먼저 거절된다(09 3-1 순서 3)
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    register("settled", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 합치기_전_기록에는_JOINED_RECORD() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        room(),
        room);

    register("joined", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOINED_RECORD"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 거주가_끝난_호실에는_새_수리_건이_없다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    register("ended", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_ENDED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 부위나_제목이_없으면_VALIDATION_FAILED() throws Exception {
    for (String json :
        new String[] {
          "{\"title\":\"누수\"}",
          "{\"roomPartId\":\"" + part + "\"}",
          "{\"roomPartId\":\"" + part + "\",\"title\":\"   \"}",
          "{\"roomPartId\":\"" + part + "\",\"title\":\"" + "가".repeat(101) + "\"}"
        }) {
      register("v-" + json.hashCode(), json)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(count("state.repair_case")).isZero();
  }

  @Test
  void 남의_부위면_NOT_FOUND이고_숨긴_부위면_VALIDATION_FAILED() throws Exception {
    UUID otherRoomPart = UUID.randomUUID();
    register("other-part", body(otherRoomPart, "누수", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    UUID hidden = part(room, true);
    register("hidden-part", body(hidden, "누수", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID stranger = UUID.randomUUID();

    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .with(loginAs(stranger))
                .header("Idempotency-Key", "stranger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 멱등_키가_없으면_400() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .with(loginAs(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void 로그인하지_않으면_401() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .header("Idempotency-Key", "anon")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isUnauthorized());
  }

  private ResultActions register(String key, String json) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static String body(UUID partId, String title, String description) {
    return "{\"roomPartId\":\""
        + partId
        + "\",\"title\":\""
        + title
        + "\""
        + (description == null ? "" : ",\"description\":\"" + description + "\"")
        + "}";
  }

  private UUID room() {
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

  /** 이 호실의 당사자 자리. 진짜 잠금(JpaRoomGateReader)이 이 행으로 당사자·측·공유 중단을 본다. */
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

  /** 그 측 자리의 회원·상태·공유 중단 시각을 바꾼다(JpaCounterpartReader가 이 값을 읽는다). */
  private void changeParty(PartySide side, UUID memberId, String status, Instant sharingStoppedAt) {
    jdbc.update(
        "UPDATE state.party SET member_id = ?, status = ?, sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        memberId,
        status,
        sharingStoppedAt == null ? null : Timestamp.from(sharingStoppedAt),
        room,
        side.name());
  }

  private UUID part(UUID roomId, boolean hidden) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, zone, name, sort_order, hidden_at, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        hidden ? Timestamp.from(STOPPED) : null);
    return id;
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }

  private Timestamp firstShownAt() {
    return jdbc.queryForObject(
        "SELECT first_shown_at FROM state.repair_case WHERE room_record_id = ?",
        Timestamp.class,
        room);
  }
}
