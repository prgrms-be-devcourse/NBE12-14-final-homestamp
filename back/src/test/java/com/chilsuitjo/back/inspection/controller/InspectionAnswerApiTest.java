package com.chilsuitjo.back.inspection.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * 06 4-7 점검 항목 응답·남은 항목 모두 확인·보충(#235)을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 세입자가 기준 측으로
 * 항목을 쓰고 임대인이 답한다. 점검 확인 요청은 가짜 {@code WaitService}가 받는다.
 */
@Import({TestcontainersConfiguration.class, InspectionApiTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionAnswerApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private InspectionApiTest.MutableClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', DATE '2026-03-01', 'LIVING', 'TENANT',
                CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
  }

  // ---- 응답 ----

  @Test
  void 상대가_답하면_도장이_찍히고_마지막_미응답에_답하면_확인_요청이_답함이_된다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    respond(landlord, items[0], "a1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(InspectionController.REPLAYED))
        .andExpect(jsonPath("$.targetId").value(items[0].toString()))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.state").value("ACK"));

    Map<String, Object> row = itemRow(items[0]);
    assertThat(row).containsEntry("status", "ACK");
    assertThat(row.get("confirmed_version_id")).isEqualTo(row.get("current_version_id"));
    // 아직 답하지 않은 항목이 남아 요청은 열려 있다
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);

    // 보류도 답이라 마지막 미응답이 보류여도 요청이 닫힌다(#235)
    respond(landlord, items[1], "a2", "NEED_MORE", "사진이 흐려요", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NEED_MORE"));
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM record.action WHERE inspection_item_id = ?"
                    + " AND type = 'RESPOND_ITEM'",
                String.class,
                items[1]))
        .isEqualTo("사진이 흐려요");
  }

  @Test
  void 확인_없음_항목도_미응답으로_세어_답하기_전에는_요청이_닫히지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    jdbc.update("UPDATE state.inspection_item SET status = 'UNCONFIRMED' WHERE id = ?", items[1]);
    waits.markUnconfirmed(target(inspection), RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);

    respond(landlord, items[0], "a1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.UNCONFIRMED);

    respond(landlord, items[1], "a2", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACK"));
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 같은_키로_다시_보내면_처음_결과를_돌려준다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "ACK", null, 1).andExpect(status().isOk());

    respond(landlord, item, "a1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.state").value("ACK"));
    assertThat(actions(item, "RESPOND_ITEM")).isEqualTo(1);
  }

  @Test
  void 작성_중이면_INVALID_STATE이고_기준_측은_자기_항목에_답할_수_없다() throws Exception {
    UUID inspection = created(start());
    UUID item = created(addItem(inspection, "i1"));

    respond(landlord, item, "draft", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    requestConfirm(inspection);
    respond(tenant, item, "own", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    respond(landlord, item, "stale", "ACK", null, 2)
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    respond(landlord, item, "no-reason", "DISPUTE", " ", 1)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    assertThat(itemRow(item)).containsEntry("status", "PENDING");
  }

  @Test
  void 당사자가_아니거나_없는_항목이면_NOT_FOUND() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];

    respond(member(), item, "x", "ACK", null, 1).andExpect(status().isNotFound());
    respond(landlord, UUID.randomUUID(), "y", "ACK", null, 1).andExpect(status().isNotFound());
    confirmAll(member(), inspection, "z", seen(item)).andExpect(status().isNotFound());
    assertThat(itemRow(item)).containsEntry("status", "PENDING");
  }

  @Test
  void 불변식_18_기준_측이_공유를_그만둔_시각에_작성_중이던_항목에는_답할_수_없다() throws Exception {
    UUID inspection = created(start());
    UUID item = created(addItem(inspection, "i1"));
    stopSharing(PartySide.TENANT, Instant.now());
    clock.advance(Duration.ofDays(7));
    // 상대가 "지금까지 쓴 것으로" 보내도 그만둔 시각에 작성 중이던 항목은 드러나지 않는다(#218)
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "stale"))
        .andExpect(status().isOk());

    respond(landlord, item, "hidden", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    confirmAll(landlord, inspection, "hidden-all", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.items").doesNotExist())
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
  }

  // ---- 남은 항목 모두 확인 ----

  @Test
  void 모두_확인은_본_항목마다_맞아요를_남기고_확인_요청을_닫는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", hasItem("confirm-all")))
        .andExpect(jsonPath("$.items[0].availableActions", hasItem("respond")));

    confirmAll(landlord, inspection, "all", seen(items))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(inspection.toString()))
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    assertThat(itemRow(items[0])).containsEntry("status", "ACK");
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
    assertThat(itemRow(items[1]).get("confirmed_version_id"))
        .isEqualTo(itemRow(items[1]).get("current_version_id"));
    assertThat(actions(items[0], "RESPOND_ITEM") + actions(items[1], "RESPOND_ITEM")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'CONFIRM_ALL'",
                Long.class,
                inspection))
        .isEqualTo(1);
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);

    confirmAll(landlord, inspection, "all", seen(items))
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));
  }

  @Test
  void 모두_확인은_보류_이견을_빼고_답하지_않은_항목만_대상이다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);
    respond(landlord, items[0], "a1", "DISPUTE", "사실과 달라요", 1).andExpect(status().isOk());

    // 이견 항목을 함께 보내면 지금 대상과 달라 VERSION_CONFLICT
    confirmAll(landlord, inspection, "with-dispute", seen(items))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    confirmAll(landlord, inspection, "rest", seen(items[1])).andExpect(status().isOk());
    assertThat(itemRow(items[0])).containsEntry("status", "DISPUTE");
    assertThat(itemRow(items[1])).containsEntry("status", "ACK");
  }

  @Test
  void 본_판이_지금과_다르면_아무것도_남지_않는다() throws Exception {
    UUID inspection = requestedWith(2);
    UUID[] items = items(inspection);

    confirmAll(landlord, inspection, "partial", seen(items[0]))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    confirmAll(
            landlord,
            inspection,
            "old",
            "[{\"id\":\""
                + items[0]
                + "\",\"seenVersionNo\":1},{\"id\":\""
                + items[1]
                + "\",\"seenVersionNo\":2}]")
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    assertThat(itemRow(items[0])).containsEntry("status", "PENDING");
    assertThat(actions(items[0], "RESPOND_ITEM")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE inspection_id = ? AND type = 'CONFIRM_ALL'",
                Long.class,
                inspection))
        .isZero();
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);
  }

  @Test
  void 항목이_없는_점검은_하자_기록_없음으로_한_번_확인해_요청을_닫는다() throws Exception {
    UUID inspection = created(start());
    requestConfirm(inspection);
    detail(tenant, inspection)
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm-all"))));

    confirmAll(tenant, inspection, "own", "[]")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    confirmAll(landlord, inspection, "none", "[]").andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);

    confirmAll(landlord, inspection, "again", "[]")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  // ---- 보충 ----

  @Test
  void 보류_뒤_보충하면_확인_기다림으로_돌아가고_확인_요청이_다시_열린다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].availableActions", hasItem("supplement")))
        .andExpect(jsonPath("$.items[0].nextRerequestAt").doesNotExist());

    supplement(tenant, item, "s1", "{\"description\":\"밝게 다시 찍었어요\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"))
        .andExpect(jsonPath("$.versionNo").value(1));

    assertThat(itemRow(item)).containsEntry("status", "PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM record.supplement WHERE inspection_item_id = ?",
                String.class,
                item))
        .isEqualTo("밝게 다시 찍었어요");
    assertThat(confirmRequest(inspection)).contains(RequestStatus.WAITING);
    detail(tenant, inspection).andExpect(jsonPath("$.items[0].nextRerequestAt").exists());
  }

  @Test
  void 보충은_근거가_필요하고_7일에_한_번이다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(tenant, item, "empty", "{}")
        .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    supplement(landlord, item, "other", "{\"description\":\"보충\"}")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    supplement(tenant, item, "s1", "{\"description\":\"보충\"}").andExpect(status().isOk());

    respond(landlord, item, "a2", "NEED_MORE", "아직 흐려요", 1).andExpect(status().isOk());
    supplement(tenant, item, "s2", "{\"description\":\"다시 보충\"}")
        .andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    clock.advance(Duration.ofDays(7));
    supplement(tenant, item, "s3", "{\"description\":\"다시 보충\"}").andExpect(status().isOk());
  }

  @Test
  void 상대가_공유를_그만뒀으면_보충해도_확인_요청을_열지_않는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];
    respond(landlord, item, "a1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    stopSharing(PartySide.LANDLORD, Instant.now());

    supplement(tenant, item, "s1", "{\"description\":\"보충\"}").andExpect(status().isOk());

    // 그만둔 사람에게는 새 확인 요청을 만들지 않는다(03 B, #218과 같은 기준)
    assertThat(confirmRequest(inspection)).contains(RequestStatus.ANSWERED);
  }

  @Test
  void 응답_보충_입력은_pipeline_전에_막는다() throws Exception {
    UUID inspection = requestedWith(1);
    UUID item = items(inspection)[0];

    mvc.perform(
            post("/api/inspection-items/{id}/respond", item)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "no-answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenVersionNo\":1}"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    respond(landlord, item, "rrn", "DISPUTE", "900101-1234567", 1)
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    StringBuilder tooMany = new StringBuilder("[");
    for (int i = 0; i < 11; i++) {
      tooMany.append(i == 0 ? "" : ",").append("\"t").append(i).append('"');
    }
    supplement(tenant, item, "many", "{\"uploadTickets\":" + tooMany.append(']') + "}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "blank", "{\"uploadTickets\":[\" \"]}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "long", "{\"description\":\"" + "가".repeat(2001) + "\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(tenant, item, "rrn", "{\"description\":\"900101-1234567\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(actions(item, "RESPOND_ITEM") + actions(item, "SUPPLEMENT_ITEM")).isZero();
  }

  // ---- 도우미 ----

  /** 세입자가 항목 {@code count}개를 쓰고 확인 요청을 보낸 점검. */
  private UUID requestedWith(int count) throws Exception {
    UUID inspection = created(start());
    for (int i = 0; i < count; i++) {
      created(addItem(inspection, "i" + i));
    }
    requestConfirm(inspection);
    return inspection;
  }

  private UUID[] items(UUID inspection) {
    return jdbc.queryForList(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ? ORDER BY id",
            UUID.class,
            inspection)
        .toArray(UUID[]::new);
  }

  private static String seen(UUID... items) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < items.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"id\":\"")
          .append(items[i])
          .append("\",\"seenVersionNo\":1}");
    }
    return json.append(']').toString();
  }

  private ResultActions start() throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "start")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"MOVE_IN\"}"));
  }

  private ResultActions addItem(UUID inspection, String key) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\"벽지 찢어짐\",\"uploadTickets\":[]}"));
  }

  private void requestConfirm(UUID inspection) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "request"))
        .andExpect(status().isOk());
  }

  private ResultActions respond(
      UUID member, UUID item, String key, String answer, String reason, Integer seen)
      throws Exception {
    String body =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":"
            + seen
            + "}";
    return mvc.perform(
        post("/api/inspection-items/{id}/respond", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions confirmAll(UUID member, UUID inspection, String key, String seenJson)
      throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/confirm-all", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":" + seenJson + "}"));
  }

  private ResultActions supplement(UUID member, UUID item, String key, String body)
      throws Exception {
    return mvc.perform(
        post("/api/inspection-items/{id}/supplement", item)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions detail(UUID member, UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(member)));
  }

  private Optional<RequestStatus> confirmRequest(UUID inspection) {
    return waits.statusOf(target(inspection), RequestKind.INSPECTION_CONFIRM, PartySide.TENANT);
  }

  private static RequestTarget target(UUID inspection) {
    return RequestTarget.of(TargetKind.INSPECTION, inspection);
  }

  private Map<String, Object> itemRow(UUID item) {
    return jdbc.queryForMap("SELECT * FROM state.inspection_item WHERE id = ?", item);
  }

  private long actions(UUID item, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE inspection_item_id = ? AND type = ?",
        Long.class,
        item,
        type);
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
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

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }
}
