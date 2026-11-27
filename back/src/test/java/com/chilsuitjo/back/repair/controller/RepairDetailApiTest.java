package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains("start", "ask-progress", "progress-notes", "cause-notes", "photos")));
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
    // 행만 바꿔 둔다. 보이는 부담은 제안 행동으로 다시 계산하므로(제안이 없으면 미정) 드러나지 않아야 한다
    jdbc.update("UPDATE state.repair_case SET money_status = 'NO_COST' WHERE id = ?", caseId);

    detail(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.defect.ack").value("PENDING"))
        .andExpect(jsonPath("$.money.status").value("UNDECIDED"))
        .andExpect(jsonPath("$.status").value("NOT_STARTED"))
        .andExpect(jsonPath("$.content.no").value(1))
        .andExpect(jsonPath("$.availableActions").value(contains("progress-notes")));
    // 양방향: 남은 쪽도 같은 시각으로 고정되지만, 자기가 남긴 답은 자기에게 보인다
    detail(caseId, landlord)
        .andExpect(jsonPath("$.asOf").value(stoppedAt.toInstant().toString()))
        .andExpect(jsonPath("$.defect.ack").value("ACK"))
        .andExpect(jsonPath("$.money.status").value("UNDECIDED"));
  }

  @Test
  void 공유를_그만두기_전_합의된_부담은_보이고_그_뒤_상대가_동의한_부담은_보이지_않는다() throws Exception {
    // #289 리뷰: 다시 계산한 부담이 늘 미정이던 경우. 부담 제안 행동은 제안이 대상이라 돈 안건 장치가 다시 계산한다(03 3-1 "돈 포함")
    UUID agreedBefore = register();
    agree(propose(agreedBefore, tenant, "{\"lines\":[],\"noCost\":true}"), landlord);
    UUID answeredAfter = register();
    UUID waiting = propose(answeredAfter, tenant, "{\"lines\":[],\"noCost\":true}");
    stopSharing(PartySide.TENANT, Timestamp.from(Instant.now()));
    agree(waiting, landlord);

    detail(agreedBefore, tenant).andExpect(jsonPath("$.money.status").value("NO_COST"));
    detail(answeredAfter, tenant)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.money.status").value("PENDING"));
    // 내가 남긴 동의는 나에게 보인다
    detail(answeredAfter, landlord).andExpect(jsonPath("$.money.status").value("NO_COST"));
  }

  @Test
  void 상대가_탈퇴하면_남은_공유_중단이_있어도_상태는_지금_값이고_asOf는_없다() throws Exception {
    // 남은 사람은 제한이 풀려(06 CONS-3p) 실제 상태에서 행동한다. 그래서 상태는 다시 계산하지 않는다(호실 상세와 같음, #294 리뷰)
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        caseCreatedAt(caseId),
        room);

    detail(caseId, tenant)
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.defect.ack").value("ACK"));
  }

  @Test
  void 내가_공유를_그만둔_동안_상대가_수리를_시작하고_종료를_요청한_뒤_탈퇴하면_상태와_버튼이_같다() throws Exception {
    // #294 리뷰 2: 상태는 다시 계산하고 버튼은 실제 상태라 어긋나던 경우. 둘 다 지금 상태다(탈퇴 처리 전까지의 알려진 한계, decisions
    // 2026-11-27)
    UUID caseId = register();
    stopSharing(PartySide.TENANT, caseCreatedAt(caseId));
    act(caseId, landlord, "start").andExpect(status().isOk());
    act(caseId, landlord, "request-close").andExpect(status().isOk());
    withdraw(PartySide.LANDLORD);

    detail(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequestedBy").value("LANDLORD"))
        .andExpect(jsonPath("$.availableActions", hasItem("confirm-close")))
        .andExpect(jsonPath("$.availableActions", not(hasItem("start"))));
  }

  @Test
  void 탈퇴_뒤_남은_사람이_행동해도_상세가_열린다() throws Exception {
    // #294 리뷰 1: 줄어든 이력([등록, 종료 요청]) 위에 남은 사람의 행동을 다시 쌓으면 시작 전에서 종료 요청이라 INVALID_STATE가 나
    // 상세가 열리지 않던 경우. 리뷰 예시의 혼자 종료는 상대 탈퇴로는 아직 열리지 않아(SoloRule) 종료 요청으로 본다
    UUID caseId = register();
    stopSharing(PartySide.TENANT, caseCreatedAt(caseId));
    act(caseId, landlord, "start").andExpect(status().isOk());
    withdraw(PartySide.LANDLORD);
    act(caseId, tenant, "request-close").andExpect(status().isOk());

    detail(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequestedBy").value("TENANT"));
  }

  @Test
  void 탈퇴로_풀리는지는_보는_사람_기준이다() throws Exception {
    // 집주인이 공유를 그만뒀고 등록한 세입자가 탈퇴했다. 집주인에게는 상대가 탈퇴했으니 할 수 있는 일의 제한이 풀린다(06 CONS-3p, #175).
    // 상태도 지금 값이라 asOf는 없다(#294 리뷰)
    UUID caseId = register();
    Timestamp stoppedAt = caseCreatedAt(caseId);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        stoppedAt,
        room);
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.availableActions").value(hasItem("start")));
  }

  private Timestamp caseCreatedAt(UUID caseId) {
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

  /** 탈퇴 처리는 {@code sharing_stopped_at}을 지우지 않는다(decisions 2026-11-26). */
  private void withdraw(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = ?",
        room,
        side.name());
  }

  /** 부담 제안을 내고 제안 ID를 돌려준다. */
  private UUID propose(UUID caseId, UUID memberId, String body) throws Exception {
    String response =
        mvc.perform(
                post("/api/repair-cases/{id}/proposals", caseId)
                    .with(loginAs(memberId))
                    .header("Idempotency-Key", "pro-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private void agree(UUID proposalId, UUID memberId) throws Exception {
    mvc.perform(
            post("/api/proposals/{id}/agree", proposalId)
                .with(loginAs(memberId))
                .header("Idempotency-Key", "agr-" + UUID.randomUUID()))
        .andExpect(status().isOk());
  }

  private ResultActions act(UUID caseId, UUID memberId, String action) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID()));
  }

  // ---- 종료 확인 요청(#339) ----

  @Test
  void 종료를_요청하기_전에는_closeRequest가_없고_요청하면_기다림이_보인다() throws Exception {
    UUID caseId = register();
    detail(caseId, tenant).andExpect(jsonPath("$.closeRequest").doesNotExist());

    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    act(caseId, tenant, "start").andExpect(status().isOk());
    act(caseId, tenant, "request-close").andExpect(status().isOk());

    for (UUID who : new UUID[] {tenant, landlord}) {
      detail(caseId, who)
          .andExpect(jsonPath("$.closeRequest.status").value("WAITING"))
          .andExpect(jsonPath("$.closeRequest.openedAt").isNotEmpty())
          .andExpect(jsonPath("$.closeRequest.closedReason").doesNotExist())
          .andExpect(jsonPath("$.closeRequest.closedAt").doesNotExist());
    }
  }

  @Test
  void 공유를_그만둔_뒤_상대가_종료를_확인해도_그만둔_사람에게는_기다림으로_보인다() throws Exception {
    // 상세 status는 asOf로 다시 계산해 종료 확인 대기다. closeRequest도 같은 시점으로 맞춘다(불변식 18, #344 리뷰)
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    act(caseId, tenant, "start").andExpect(status().isOk());
    act(caseId, tenant, "request-close").andExpect(status().isOk());
    stopSharing(PartySide.TENANT, Timestamp.from(Instant.now()));
    act(caseId, landlord, "confirm-close").andExpect(status().isOk());

    detail(caseId, tenant)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.status").value("CLOSE_WAIT"))
        .andExpect(jsonPath("$.closeRequest.status").value("WAITING"))
        .andExpect(jsonPath("$.closeRequest.closedAt").doesNotExist());
    // 확인한 쪽은 지금 값(자기 행동은 보인다)
    detail(caseId, landlord)
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closeRequest.status").value("ANSWERED"));
  }

  @Test
  void 종료를_확인하면_closeRequest는_답한_상태로_남는다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    act(caseId, tenant, "start").andExpect(status().isOk());
    act(caseId, tenant, "request-close").andExpect(status().isOk());
    act(caseId, landlord, "confirm-close").andExpect(status().isOk());

    detail(caseId, tenant)
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.closeRequest.status").value("ANSWERED"));
  }

  // ---- 사진(#175) ----

  @Test
  void 등록과_보충_사진이_순서대로_보이고_다른_수리_건_사진은_섞이지_않는다() throws Exception {
    UUID caseId = register();
    UUID other = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    mvc.perform(
            post("/api/repair-cases/{id}/supplement", caseId)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "sup-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadTickets\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isOk());

    for (UUID viewer : new UUID[] {tenant, landlord}) {
      detail(caseId, viewer)
          .andExpect(jsonPath("$.photos.length()").value(2))
          .andExpect(jsonPath("$.photos[0].sortOrder").value(0))
          .andExpect(jsonPath("$.photos[1].sortOrder").value(1))
          .andExpect(jsonPath("$.photos[0].stage").value("BEFORE"))
          .andExpect(jsonPath("$.photos[1].stage").value("BEFORE"))
          .andExpect(jsonPath("$.photos[0].uploadedBy").value("TENANT"))
          .andExpect(jsonPath("$.photos[0].target.id").value(caseId.toString()))
          .andExpect(jsonPath("$.photos[0].erased").doesNotExist());
    }
    detail(other, tenant)
        .andExpect(jsonPath("$.photos.length()").value(1))
        .andExpect(jsonPath("$.photos[0].target.id").value(other.toString()));
  }

  @Test
  void 상대_쪽은_확인_기다림이면_하자_응답을_누를_수_있다() throws Exception {
    UUID caseId = register();

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.registeredBy").value("TENANT"))
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains(
                        "respond",
                        "start",
                        "ask-progress",
                        "progress-notes",
                        "cause-notes",
                        "photos")));
  }

  @Test
  void 보류면_등록한_쪽은_보충을_상대는_맞아요로_바꾸기를_누를_수_있다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());

    detail(caseId, tenant)
        .andExpect(jsonPath("$.defect.ack").value("NEED_MORE"))
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains(
                        "supplement",
                        "start",
                        "ask-progress",
                        "progress-notes",
                        "cause-notes",
                        "photos")));
    detail(caseId, landlord)
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains(
                        "respond",
                        "start",
                        "ask-progress",
                        "progress-notes",
                        "cause-notes",
                        "photos")));
  }

  @Test
  void 맞아요_뒤에는_하자_버튼이_없다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    detail(caseId, landlord)
        .andExpect(jsonPath("$.defect.ack").value("ACK"))
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains("start", "ask-progress", "progress-notes", "cause-notes", "photos")));
    detail(caseId, tenant)
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains("start", "ask-progress", "progress-notes", "cause-notes", "photos")));
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
        .andExpect(
            jsonPath("$.availableActions")
                .value(
                    contains("start", "ask-progress", "progress-notes", "cause-notes", "photos")));
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

    // 수리 시작·진행 상황 묻기는 거주 종료 뒤 막히고 남기기만 된다(03 A·D, #177 리뷰)
    detail(caseId, tenant)
        .andExpect(jsonPath("$.availableActions").value(contains("progress-notes", "cause-notes")));
    detail(caseId, landlord)
        .andExpect(
            jsonPath("$.availableActions")
                .value(contains("respond", "progress-notes", "cause-notes")));
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
    // 집주인이 공유를 그만뒀다. 그 전에 남긴 하자는 계속 보이지만 집주인은 새 행동을 할 수 없다(pipeline SHARING_STOPPED).
    // 나만 보는 진행 상황 남기기만 된다(03 B)
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at + interval '1 second' FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        caseId,
        room);

    detail(caseId, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableActions").value(contains("progress-notes")));
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

  /** 기본 목록(V7)에서 복사된 "방 › 천장". 공유를 그만둔 뒤 하자 등록은 보이는 부위로 판단해(#183) 실제 호실처럼 템플릿을 가리킨다. */
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
