package com.chilsuitjo.back.inspection.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 06 INSP-1a 입주 점검 시작·작성 중 항목·조회와 INSP-2b 입주일 저장, #190 호실 상세 입주일 숨김을 보안·pipeline·진짜 행동 기록
 * 저장(PostgreSQL)까지 띄워 본다. 사진은 가짜 사진 창구({@code FakePhotoService})가 받는다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionApiTest {

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
    room = room(LocalDate.parse("2026-03-01"));
    tenant = member();
    landlord = member();
    part = part();
    seat(PartySide.TENANT, tenant, "CONNECTED");
    seat(PartySide.LANDLORD, landlord, "CONNECTED");
  }

  // ---- 시작 ----

  @Test
  void 시작하면_작성_중_점검과_행동이_함께_생기고_아직_상대에게_보이지_않는다() throws Exception {
    UUID id = created(start(tenant, "k1", "MOVE_IN", null));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", id);
    assertThat(row)
        .containsEntry("room_record_id", room)
        .containsEntry("kind", "MOVE_IN")
        .containsEntry("author_side", "TENANT")
        .containsEntry("status", "DRAFT")
        .containsEntry("late", false);
    assertThat(row.get("first_shown_at")).isNull();

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE inspection_id = ?", id);
    assertThat(action)
        .containsEntry("type", "START_INSPECTION")
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT");
    // 입주일이 이미 있는 호실이라 채운 입주일이 없다
    assertThat(action.get("payload").toString()).isEqualTo("{\"kind\": \"MOVE_IN\"}");
    assertThat(row.get("created_at")).isEqualTo(action.get("created_at"));
  }

  @Test
  void 같은_키로_다시_보내면_처음_점검을_돌려준다() throws Exception {
    UUID first = created(start(tenant, "same", "MOVE_IN", null));

    start(tenant, "same", "MOVE_IN", null)
        .andExpect(status().isCreated())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(count("state.inspection")).isEqualTo(1);
  }

  @Test
  void 상대가_먼저_시작했으면_INSPECTION_EXISTS() throws Exception {
    created(start(landlord, "a", "MOVE_IN", null));

    start(tenant, "b", "MOVE_IN", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSPECTION_EXISTS"));
    assertThat(
            jdbc.queryForObject(
                "SELECT author_side FROM state.inspection WHERE room_record_id = ?",
                String.class,
                room))
        .isEqualTo("LANDLORD");
  }

  @Test
  void 동시에_시작하면_한쪽만_기준이_되고_다른_쪽은_INSPECTION_EXISTS() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (UUID member : List.of(tenant, landlord)) {
        Callable<Integer> call =
            () -> {
              go.await();
              return start(member, "race-" + member, "MOVE_IN", null)
                  .andReturn()
                  .getResponse()
                  .getStatus();
            };
        results.add(pool.submit(call));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("state.inspection")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE room_record_id = ?"
                    + " AND type = 'START_INSPECTION'",
                Long.class,
                room))
        .isEqualTo(1);
  }

  @Test
  void 당사자가_아니면_NOT_FOUND() throws Exception {
    start(member(), "x", "MOVE_IN", null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 현재_구현_범위의_한계로_퇴거_점검은_시작할_수_없다() throws Exception {
    start(tenant, "out", "MOVE_OUT", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(count("state.inspection")).isZero();
  }

  // ---- 입주일(INSP-2b) ----

  @Test
  void INSP_2b_입주일이_비었는데_보내지_않으면_MOVE_IN_DATE_REQUIRED() throws Exception {
    clearMoveInDate();

    start(tenant, "no-date", "MOVE_IN", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MOVE_IN_DATE_REQUIRED"));
    assertThat(count("state.inspection")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void INSP_2b_입주일을_함께_보내면_점검과_입주일이_함께_저장된다() throws Exception {
    clearMoveInDate();

    UUID id = created(start(tenant, "with-date", "MOVE_IN", "2026-02-15"));

    assertThat(moveInDate()).isEqualTo(LocalDate.parse("2026-02-15"));
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM record.action WHERE inspection_id = ?", String.class, id);
    assertThat(payload).contains("\"moveInDate\": \"2026-02-15\"");
  }

  @Test
  void 입주일이_이미_있는데_보내면_VALIDATION_FAILED이고_입주일은_그대로다() throws Exception {
    start(tenant, "change", "MOVE_IN", "2026-02-15")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(moveInDate()).isEqualTo(LocalDate.parse("2026-03-01"));
    assertThat(count("state.inspection")).isZero();
  }

  // ---- 항목 더하기 ----

  @Test
  void 기준_측이_작성_중에_항목을_더하면_항목과_판_1과_행동이_함께_생긴다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", tickets(2)));

    Map<String, Object> head =
        jdbc.queryForMap("SELECT * FROM state.inspection_item WHERE id = ?", item);
    assertThat(head)
        .containsEntry("inspection_id", inspection)
        .containsEntry("status", "PENDING")
        .containsEntry("added_by_side", "TENANT")
        .containsEntry("added_after_lock", false)
        .containsEntry("added_after_end", false);
    Map<String, Object> version =
        jdbc.queryForMap(
            "SELECT * FROM record.inspection_item_version WHERE inspection_item_id = ?", item);
    assertThat(version)
        .containsEntry("id", head.get("current_version_id"))
        .containsEntry("no", 1)
        .containsEntry("room_part_id", part)
        .containsEntry("description", "벽지 찢어짐");

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE inspection_item_id = ?", item);
    assertThat(action).containsEntry("type", "ADD_ITEM").containsEntry("actor_side", "TENANT");
    String payload = action.get("payload").toString();
    assertThat(payload).contains("\"inspectionId\": \"" + inspection + "\"").contains("\"photos\"");
    // 새 판을 만드는 행동이라 그 판 #1을 지문으로 남긴다(안내서 3-3). 칸 순서도 형식의 일부다.
    DigestInput expected =
        new DigestInput(
            "inspection_item_version",
            (UUID) version.get("id"),
            1,
            List.of(
                new DigestField("room_part_id", part.toString()),
                new DigestField("description", "벽지 찢어짐")));
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(expected));
  }

  @Test
  void 사진_없이도_항목을_더할_수_있다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    created(addItem(tenant, inspection, "no-photo", "창틀 곰팡이", "[]"));

    assertThat(count("state.inspection_item")).isEqualTo(1);
  }

  @Test
  void INSP_1a_작성_중에는_상대가_항목을_더할_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    addItem(landlord, inspection, "other", "방충망 찢어짐", "[]")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(count("state.inspection_item")).isZero();
  }

  @Test
  void 설명이_없거나_주민번호_형태면_거절한다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    addItem(tenant, inspection, "blank", " ", "[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    addItem(tenant, inspection, "rrn", "900101-1234567 얼룩", "[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(count("state.inspection_item")).isZero();
  }

  @Test
  void 남의_점검에는_항목을_더할_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    addItem(member(), inspection, "stranger", "얼룩", "[]")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- 조회(INSP-1a) ----

  @Test
  void INSP_1a_작성_중인_점검은_상대에게_작성_중만_보인다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.authorSide").value("TENANT"))
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.items").doesNotExist())
        .andExpect(jsonPath("$.availableActions").isEmpty());
  }

  @Test
  void 기준_측은_작성_중인_항목과_부위_이름을_본다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(tenant, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("MOVE_IN"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].version.no").value(1))
        .andExpect(jsonPath("$.items[0].version.partName").value("천장"))
        .andExpect(jsonPath("$.items[0].version.description").value("벽지 찢어짐"))
        .andExpect(jsonPath("$.availableActions[0]").value("items"));
  }

  @Test
  void 당사자가_아니면_점검을_볼_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    detail(member(), inspection).andExpect(status().isNotFound());
    detail(tenant, UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void 항목이_없으면_빈_목록이고_항목_사진도_함께_보인다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    detail(tenant, inspection).andExpect(jsonPath("$.items").isEmpty());

    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", tickets(2)));

    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].photos.length()").value(2))
        .andExpect(jsonPath("$.items[0].photos[0].stage").value("MOVE_IN"));
  }

  @Test
  void 공유를_그만둔_뒤_상대가_시작한_점검은_그만둔_사람에게_NOT_FOUND() throws Exception {
    Instant stopped = Instant.now().minusSeconds(3600);
    stopSharing(PartySide.LANDLORD, stopped);

    UUID inspection = created(start(tenant, "after-stop", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(landlord, inspection).andExpect(status().isNotFound());
    // 남은 사람은 자기 점검과 항목을 그대로 보고, 보이는 범위를 고정한 시각을 받는다
    detail(tenant, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void 공유를_그만두기_전에_시작한_점검은_그만둔_사람도_볼_수_있다() throws Exception {
    UUID inspection = created(start(tenant, "before-stop", "MOVE_IN", null));
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.items").doesNotExist());
  }

  // ---- 호실 상세의 입주일(#190, 불변식 18) ----

  @Test
  void 공유를_그만둔_뒤_상대가_채운_입주일은_그만둔_사람에게_보이지_않는다() throws Exception {
    clearMoveInDate();
    stopSharing(PartySide.LANDLORD, Instant.now().minusSeconds(3600));

    created(start(tenant, "after-stop", "MOVE_IN", "2026-02-15"));

    roomDetail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").doesNotExist());
    // 채운 사람에게는 보인다
    roomDetail(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").value("2026-02-15"));
  }

  @Test
  void 공유를_그만두기_전에_채운_입주일은_그대로_보인다() throws Exception {
    clearMoveInDate();
    created(start(tenant, "before-stop", "MOVE_IN", "2026-02-15"));
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));

    roomDetail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").value("2026-02-15"));
  }

  // ---- 준비물 ----

  private ResultActions start(UUID member, String key, String kind, String moveInDate)
      throws Exception {
    String body =
        "{\"kind\":\""
            + kind
            + "\""
            + (moveInDate == null ? "" : ",\"moveInDate\":\"" + moveInDate + "\"")
            + "}";
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions addItem(
      UUID member, UUID inspection, String key, String description, String ticketsJson)
      throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\""
                    + description
                    + "\",\"uploadTickets\":"
                    + ticketsJson
                    + "}"));
  }

  private ResultActions detail(UUID member, UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(member)));
  }

  private ResultActions roomDetail(UUID member) throws Exception {
    return mvc.perform(get("/api/rooms/{id}", room).with(loginAs(member)));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private static String tickets(int count) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    return json.append(']').toString();
  }

  private UUID room(LocalDate moveInDate) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', ?, 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        Date.valueOf(moveInDate));
    return id;
  }

  private void clearMoveInDate() {
    jdbc.update("UPDATE state.room_record SET move_in_date = NULL WHERE id = ?", room);
  }

  private LocalDate moveInDate() {
    return jdbc.queryForObject(
            "SELECT move_in_date FROM state.room_record WHERE id = ?", Date.class, room)
        .toLocalDate();
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

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

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
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

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }
}
