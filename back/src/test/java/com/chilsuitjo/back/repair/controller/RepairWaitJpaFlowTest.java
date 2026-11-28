package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.service.ActionRequestContext;
import com.chilsuitjo.back.action.service.RequestContextTestSupport;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.dto.RequestView;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.JpaWaitService;
import com.chilsuitjo.back.notification.service.WaitQuery;
import com.chilsuitjo.back.notification.service.WaitService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영처럼 실제 DB 응답 요청({@link JpaWaitService})으로 수리 흐름을 띄운다(테스트 전용 가짜를 뺌). 원인 행동과 같은 트랜잭션에서 요청 행이 생기고
 * 답으로 바뀌는지, 새로 만든 진행 상황을 가리키는 요청도 외래 키에 걸리지 않고 저장되는지, 다른 도메인이 읽는 {@link WaitQuery}가 같은 행을 보는지 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.notification.wait-service=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairWaitJpaFlowTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private WaitService waitService;
  @Autowired private WaitQuery waitQuery;
  @Autowired private ActionRequestContext requestContext;
  @Autowired private PlatformTransactionManager transactionManager;
  @PersistenceContext private EntityManager entityManager;

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
  void 운영_빈은_실제_DB_구현이다() {
    assertThat(waitService).isInstanceOf(JpaWaitService.class);
    assertThat(waitQuery).isInstanceOf(JpaWaitService.class);
  }

  @Test
  void 종료_요청은_DB에_요청을_열고_상대가_확인하면_답함으로_닫힌다() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());

    act(caseId, tenant, "request-close", null).andExpect(status().isOk());

    List<Map<String, Object>> rows = requests(caseId, "CLOSE_CONFIRM");
    assertThat(rows)
        .singleElement()
        .satisfies(row -> {
          assertThat(row)
              .containsEntry("status", "WAITING")
              .containsEntry("requested_by_side", "TENANT");
          assertThat(row.get("first_notified_at")).isNull();
        });
    // 같은 행동의 시각으로 만든다(decisions 2026-11-19)
    assertThat(rows.getFirst().get("created_at")).isEqualTo(actionAt(caseId, "REQUEST_CLOSE"));
    // 등록이 연 하자 확인(#324)과 종료 확인이 함께 기다린다
    assertThat(waitQuery.awaiting(room, PartySide.LANDLORD))
        .extracting(RequestView::kind)
        .containsExactlyInAnyOrder(RequestKind.DEFECT_CONFIRM, RequestKind.CLOSE_CONFIRM);

    act(caseId, landlord, "confirm-close", null).andExpect(status().isOk());

    assertThat(requests(caseId, "CLOSE_CONFIRM"))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "ANSWERED"));
    assertThat(
            waitQuery.latest(
                RequestKind.CLOSE_CONFIRM, RequestTarget.of(TargetKind.REPAIR_CASE, caseId)))
        .map(RequestView::status)
        .contains(RequestStatus.ANSWERED);
    assertThat(waitQuery.awaiting(room, PartySide.LANDLORD))
        .extracting(RequestView::kind)
        .doesNotContain(RequestKind.CLOSE_CONFIRM);
  }

  @Test
  void 수리_목록_waiting과_상세_closeRequest가_실제_DB_요청으로_채워진다() throws Exception {
    // 가짜가 아니라 JpaWaitService가 주는 값이 repair 응답까지 오는지(#339, #344 리뷰)
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());

    mvc.perform(get("/api/rooms/{id}/repair-cases", room).with(loginAs(landlord)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].waiting.length()").value(2))
        .andExpect(
            jsonPath("$.items[0].waiting[*].kind")
                .value(containsInAnyOrder("DEFECT_CONFIRM", "CLOSE_CONFIRM")))
        .andExpect(
            jsonPath("$.items[0].waiting[*].answeringSide").value(everyItem(is("LANDLORD"))));
    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closeRequest.status").value("WAITING"))
        .andExpect(jsonPath("$.closeRequest.openedAt").isNotEmpty());

    act(caseId, landlord, "confirm-close", null).andExpect(status().isOk());

    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(jsonPath("$.closeRequest.status").value("ANSWERED"));
    mvc.perform(get("/api/rooms/{id}/repair-cases", room).with(loginAs(landlord)))
        .andExpect(jsonPath("$.items[0].waiting[*].kind").value(not(hasItem("CLOSE_CONFIRM"))));
  }

  // ---- 공유를 그만둔 뒤 상세 closeRequest(#382) ----

  @Test
  void 공유_중단_때_닫힌_종료_확인은_그만둔_사람에게도_닫힌_그대로_보인다() throws Exception {
    // asOf나 그 전에 닫힌 요청은 그 시각에도 닫혀 있었다. 공유 중단 closeAll과 같은 닫기를 직접 넣는다(03 B)
    UUID caseId = requestedClose();
    Timestamp stoppedAt = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    stopSharing(PartySide.TENANT, stoppedAt);
    closeCloseRequest(caseId, stoppedAt);

    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequest.status").value("CLOSED"))
        .andExpect(jsonPath("$.closeRequest.closedReason").value("SHARING_STOPPED"))
        .andExpect(jsonPath("$.closeRequest.closedAt").value(stoppedAt.toInstant().toString()));
  }

  @Test
  void 공유_중단_뒤에_닫힌_종료_확인은_그만둔_사람에게_열린_모습으로_보인다() throws Exception {
    UUID caseId = requestedClose();
    Instant stoppedAt = Instant.now();
    stopSharing(PartySide.TENANT, Timestamp.from(stoppedAt));
    closeCloseRequest(caseId, Timestamp.from(stoppedAt.plusSeconds(60)));

    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequest.status").value("WAITING"))
        .andExpect(jsonPath("$.closeRequest.closedReason").doesNotExist())
        .andExpect(jsonPath("$.closeRequest.closedAt").doesNotExist());
  }

  @Test
  void 공유_중단_뒤_상대가_답한_종료_확인은_닫힌_시각이_없어도_열린_모습으로_보인다() throws Exception {
    // 답함은 closed_at이 없다(V1 CHECK). 그래도 상대의 중단 뒤 확인이 드러나지 않는다(불변식 18)
    UUID caseId = requestedClose();
    stopSharing(PartySide.TENANT, Timestamp.from(Instant.now()));
    act(caseId, landlord, "confirm-close", null).andExpect(status().isOk());
    assertThat(requests(caseId, "CLOSE_CONFIRM").getFirst()).containsEntry("status", "ANSWERED");

    mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(tenant)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequest.status").value("WAITING"));
  }

  @Test
  void 상황이_바뀌었어요는_새_진행_상황을_가리키는_요청을_DB에_연다() throws Exception {
    UUID caseId = register();
    // 하자를 둘 다 확인한 건에만 켤 수 있다(REP-6d)
    act(caseId, landlord, "respond", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isOk());

    act(caseId, tenant, "progress-notes", "{\"text\":\"구멍이 더 커졌어요\",\"asksCheck\":true}")
        .andExpect(status().isCreated());

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT r.status, r.requested_by_side FROM state.response_request r"
                + " JOIN record.progress_note n ON n.id = r.progress_note_id"
                + " WHERE r.kind = 'SITUATION_CHECK' AND n.repair_case_id = ?",
            caseId);
    assertThat(row).containsEntry("status", "WAITING").containsEntry("requested_by_side", "TENANT");
  }

  @Test
  void 원인_행동의_트랜잭션이_되돌아가면_요청도_남지_않는다() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    // save 안에서 요청을 연 뒤 그 행동이 실패한 것과 같다(MANDATORY라 같은 트랜잭션에 합류)
    tx.executeWithoutResult(status -> {
      RequestContextTestSupport.within(
          requestContext,
          Instant.parse("2026-11-27T00:00:00Z"),
          () ->
              waitService.open(
                  new OpenRequestCommand(
                      room,
                      RequestKind.CORRECTION_CONFIRM,
                      RequestTarget.roomRecord(room),
                      PartySide.TENANT)));
      entityManager.flush();
      assertThat(roomRequests()).isEqualTo(1);
      status.setRollbackOnly();
    });

    assertThat(roomRequests()).isZero();
  }

  // ---- 도우미 ----

  private int roomRequests() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.response_request WHERE room_record_id = ?",
        Integer.class,
        room);
  }

  private List<Map<String, Object>> requests(UUID caseId, String kind) {
    return jdbc.queryForList(
        "SELECT status, requested_by_side, first_notified_at, created_at"
            + " FROM state.response_request WHERE repair_case_id = ? AND kind = ?",
        caseId,
        kind);
  }

  /** 세입자가 시작하고 종료를 요청해 둔 수리 건. */
  private UUID requestedClose() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());
    return caseId;
  }

  private void stopSharing(PartySide side, Timestamp at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        at,
        room,
        side.name());
  }

  /** 공유 중단 {@code closeAll}이 할 닫기를 직접 넣는다(아직 구현 전, #370 A-6). */
  private void closeCloseRequest(UUID caseId, Timestamp at) {
    int updated =
        jdbc.update(
            "UPDATE state.response_request SET status = 'CLOSED', closed_reason = 'SHARING_STOPPED',"
                + " closed_at = ? WHERE kind = 'CLOSE_CONFIRM' AND repair_case_id = ?",
            at,
            caseId);
    assertThat(updated).isEqualTo(1);
  }

  private Object actionAt(UUID caseId, String type) {
    return jdbc.queryForObject(
        "SELECT created_at FROM record.action WHERE repair_case_id = ? AND type = ?",
        Object.class,
        caseId,
        type);
  }

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
                            + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\","
                            + "\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions act(UUID caseId, UUID memberId, String action, String json)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID());
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(request);
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
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '방' AND t.name = '천장'
        """,
        id,
        room);
    return id;
  }
}
