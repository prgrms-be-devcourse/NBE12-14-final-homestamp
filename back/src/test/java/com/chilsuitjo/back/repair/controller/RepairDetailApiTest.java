package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * 수리 건 상세({@code GET /api/repair-cases/{id}}, 06 4-6·S-12, #164)를 보안·진짜 DB까지 띄워 본다. 버튼({@code
 * availableActions})이 실제 요청의 거절과 같은지 함께 본다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairDetailApiTest {

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
  void 등록한_쪽은_수리_건_내용을_보고_확인_기다림에는_누를_버튼이_없다() throws Exception {
    UUID caseId = register();

    detail(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(caseId.toString()))
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()))
        .andExpect(jsonPath("$.mySide").value("TENANT"))
        .andExpect(jsonPath("$.registeredBy").value("TENANT"))
        .andExpect(jsonPath("$.status").value("NOT_STARTED"))
        .andExpect(jsonPath("$.solo").value(false))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.content.no").value(1))
        .andExpect(jsonPath("$.content.roomPartId").value(part.toString()))
        .andExpect(jsonPath("$.content.partName").value("천장"))
        .andExpect(jsonPath("$.content.title").value("천장 누수"))
        .andExpect(jsonPath("$.content.description").value("비 오면 물이 떨어져요"))
        .andExpect(jsonPath("$.defect.ack").value("PENDING"))
        .andExpect(jsonPath("$.defect.nextRerequestAt").doesNotExist())
        .andExpect(jsonPath("$.money.status").value("UNDECIDED"))
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.availableActions").isEmpty());
  }

  @Test
  void 공유를_그만둔_뒤_상대가_바꾼_상태는_그만둔_쪽에_보이지_않고_그_시각_기준으로_고정된다() throws Exception {
    // 중단 전 수리 건 → 세입자가 공유를 그만둠 → 집주인이 하자에 답하고 부담 상태가 바뀜 → 세입자가 상세를 봄(03 6-6 9번, #166 리뷰)
    UUID caseId = register();
    Timestamp stoppedAt =
        jdbc.queryForObject(
            "SELECT created_at FROM state.repair_case WHERE id = ?", Timestamp.class, caseId);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        stoppedAt,
        room);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    // 부담 상태를 바꾸는 행동은 아직 없어 행만 바꿔 둔다. 보이는 상태는 행동으로 다시 계산하므로 드러나지 않아야 한다
    jdbc.update("UPDATE state.repair_case SET money_status = 'NO_COST' WHERE id = ?", caseId);

    detail(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.defect.ack").value("PENDING"))
        .andExpect(jsonPath("$.money.status").value("UNDECIDED"))
        .andExpect(jsonPath("$.status").value("NOT_STARTED"))
        .andExpect(jsonPath("$.content.no").value(1))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    // 양방향: 남은 쪽도 같은 시각으로 고정되지만, 자기가 남긴 답은 자기에게 보인다
    detail(caseId, landlord)
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.defect.ack").value("ACK"))
        .andExpect(jsonPath("$.money.status").value("UNDECIDED"));
  }

  @Test
  void 상대가_탈퇴해_풀린_공유_중단은_고정하지_않는다() throws Exception {
    // 탈퇴하면 남은 공유 중단 시각을 보지 않는다(06 CONS-3q)
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', sharing_stopped_at ="
            + " (SELECT created_at FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        caseId,
        room);

    detail(caseId, tenant)
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.defect.ack").value("ACK"));
  }

  @Test
  void 상대_쪽은_확인_기다림이면_하자_응답을_누를_수_있다() throws Exception {
    UUID caseId = register();

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.registeredBy").value("TENANT"))
        .andExpect(jsonPath("$.availableActions.length()").value(1))
        .andExpect(jsonPath("$.availableActions[0]").value("respond"));
  }

  @Test
  void 보류면_등록한_쪽은_보충을_상대는_맞아요로_바꾸기를_누를_수_있다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());

    detail(caseId, tenant)
        .andExpect(jsonPath("$.defect.ack").value("NEED_MORE"))
        .andExpect(jsonPath("$.availableActions.length()").value(1))
        .andExpect(jsonPath("$.availableActions[0]").value("supplement"));
    detail(caseId, landlord)
        .andExpect(jsonPath("$.availableActions.length()").value(1))
        .andExpect(jsonPath("$.availableActions[0]").value("respond"));
  }

  @Test
  void 맞아요_뒤에는_하자_버튼이_없다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    detail(caseId, landlord)
        .andExpect(jsonPath("$.defect.ack").value("ACK"))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    detail(caseId, tenant).andExpect(jsonPath("$.availableActions").isEmpty());
  }

  @Test
  void 보충한_뒤에는_다음_보충_시각이_7일_뒤로_보이고_7일_안에는_보충_버튼이_없다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    supplement(caseId, tenant, "다시 찍었어요").andExpect(status().isOk());
    respond(caseId, landlord, "NEED_MORE", "아직 안 보여요").andExpect(status().isOk());

    Instant supplementedAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE repair_case_id = ? AND type ="
                    + " 'SUPPLEMENT'",
                Timestamp.class,
                caseId)
            .toInstant();
    detail(caseId, tenant)
        .andExpect(jsonPath("$.defect.ack").value("NEED_MORE"))
        .andExpect(
            jsonPath("$.defect.nextRerequestAt")
                .value(supplementedAt.plus(Duration.ofDays(7)).toString()))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    // 실제 요청도 같은 판단으로 거절된다
    supplement(caseId, tenant, "한 번 더 찍었어요").andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
  }

  @Test
  void 거주가_끝나면_늦은_답은_보이고_보충은_보이지_않는다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    detail(caseId, tenant).andExpect(jsonPath("$.availableActions").isEmpty());
    detail(caseId, landlord).andExpect(jsonPath("$.availableActions[0]").value("respond"));
  }

  @Test
  void 정산이_끝난_호실은_볼_수는_있고_누를_버튼은_없다() throws Exception {
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableActions").isEmpty());
  }

  @Test
  void 공유를_그만둔_쪽에게는_하자_응답_버튼이_없다() throws Exception {
    // 집주인이 공유를 그만뒀다. 그 전에 남긴 하자는 계속 보이지만 집주인은 새 행동을 할 수 없다(pipeline SHARING_STOPPED)
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at + interval '1 second' FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        caseId,
        room);

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableActions").isEmpty());
    respond(caseId, landlord, "ACK", null).andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
  }

  @Test
  void 공유를_그만둔_뒤_등록한_하자는_상대에게_NOT_FOUND이고_등록한_쪽은_본다() throws Exception {
    // 세입자가 공유를 그만둔 뒤 등록한 하자는 "나만 보는 기록"이다(03 불변식 18)
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - interval '1 hour'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID caseId = register();

    detail(caseId, landlord)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    detail(caseId, tenant).andExpect(status().isOk());
  }

  @Test
  void 없는_수리_건이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID caseId = register();

    detail(UUID.randomUUID(), tenant)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    detail(caseId, member())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 로그인하지_않으면_LOGIN_REQUIRED() throws Exception {
    UUID caseId = register();

    mvc.perform(get("/api/repair-cases/{id}", caseId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  // ---- 준비물 ----

  private ResultActions detail(UUID caseId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)));
  }

  /** 세입자가 하자를 등록하고 수리 건 ID를 돌려준다. */
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
                            + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\"}"))
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
