package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.action.rule.Replay;
import com.chilsuitjo.back.action.service.ActionReader;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import com.chilsuitjo.back.repair.rule.RepairState;
import com.chilsuitjo.back.repair.rule.RepairTransitions;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 REP-2 하자 응답·보충을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#155). 응답 요청은 가짜({@code
 * FakeWaitService})라 상태만 확인한다. 보충 사진은 아직 없다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairDefectApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private ActionReader actions;
  @Autowired private RepairTransitions rules;
  @Autowired private PlatformTransactionManager transactionManager;

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
    // 세입자(등록 측)와 집주인(답하는 측) 모두 연결됨
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
  }

  // ---- 하자 응답 ----

  @Test
  void REP_2a_상대가_맞아요를_누르면_하자_인정만_바뀌고_확인_요청이_닫힌다() throws Exception {
    UUID caseId = register();
    waits.open(
        new OpenRequestCommand(
            room, RequestKind.DEFECT_CONFIRM, defectRequest(caseId), PartySide.TENANT));

    respond(caseId, landlord, "r1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RepairController.REPLAYED))
        .andExpect(jsonPath("$.targetId").value(caseId.toString()))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.state").value("ACK"))
        .andExpect(jsonPath("$.replayed").value(false));

    assertThat(caseRow(caseId))
        .containsEntry("defect_ack", "ACK")
        .containsEntry("status", "NOT_STARTED");
    Map<String, Object> action = action(caseId, "RESPOND_DEFECT");
    assertThat(action)
        .containsEntry("actor_member_id", landlord)
        .containsEntry("actor_side", "LANDLORD")
        .containsEntry("seen_version_no", 1);
    assertThat(action.get("reason")).isNull();
    assertThat(action.get("payload").toString()).isEqualTo("{\"answer\": \"ACK\"}");
    // 본 판에 대고 하는 행동이라 본 판 #1이 지문이다(안내서 3-3)
    assertThat((byte[]) action.get("content_digest"))
        .isEqualTo(ContentDigest.compute(contentDigest(caseId, "천장 누수", "비 오면 물이 떨어져요")));
    assertThat(waits.statusOf(defectRequest(caseId), RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void REP_9a_이견의_사유는_행동의_reason에_남고_payload에는_답만_남는다() throws Exception {
    UUID caseId = register();

    respond(caseId, landlord, "r1", "DISPUTE", "원래 있던 얼룩이에요", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISPUTE"));

    Map<String, Object> action = action(caseId, "RESPOND_DEFECT");
    assertThat(action).containsEntry("reason", "원래 있던 얼룩이에요");
    assertThat(action.get("payload").toString()).isEqualTo("{\"answer\": \"DISPUTE\"}");
    assertThat(caseRow(caseId)).containsEntry("defect_ack", "DISPUTE");
  }

  @Test
  void REP_2c_보류에_사유가_없으면_REASON_REQUIRED() throws Exception {
    UUID caseId = register();

    respond(caseId, landlord, "r1", "NEED_MORE", "  ", 1)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    assertThat(caseRow(caseId)).containsEntry("defect_ack", "PENDING");
  }

  @Test
  void REP_2b_내가_등록한_하자는_내가_확인할_수_없다() throws Exception {
    UUID caseId = register();

    respond(caseId, tenant, "r1", "ACK", null, 1)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void REP_2f_맞아요_뒤에_이견을_누르면_ACK_FINAL() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "ACK", null, 1).andExpect(status().isOk());

    respond(caseId, landlord, "r2", "DISPUTE", "다시 보니 달라요", 1)
        .andExpect(jsonPath("$.code").value("ACK_FINAL"));
  }

  @Test
  void 본_판_번호가_다르면_VERSION_CONFLICT() throws Exception {
    UUID caseId = register();

    respond(caseId, landlord, "r1", "ACK", null, 2)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void 같은_키로_다시_보내면_행동을_더_남기지_않고_지금_상태를_돌려준다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "same", "ACK", null, 1).andExpect(status().isOk());

    respond(caseId, landlord, "same", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.state").value("ACK"))
        .andExpect(jsonPath("$.replayed").value(true));

    assertThat(countActions(caseId, "RESPOND_DEFECT")).isEqualTo(1);
  }

  @Test
  void 없는_수리_건이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID caseId = register();

    respond(UUID.randomUUID(), landlord, "r1", "ACK", null, 1)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    respond(caseId, member(), "r2", "ACK", null, 1)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 답이_없으면_VALIDATION_FAILED() throws Exception {
    UUID caseId = register();

    mvc.perform(
            post("/api/repair-cases/{id}/respond", caseId)
                .with(loginAs(landlord))
                .header("Idempotency-Key", "r1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenVersionNo\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void 거주가_끝나도_정산_완료_전이면_늦게_답할_수_있다() throws Exception {
    UUID caseId = register();
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    respond(caseId, landlord, "r1", "ACK", null, 1).andExpect(status().isOk());
  }

  @Test
  void 정산이_끝난_호실에는_SETTLED() throws Exception {
    UUID caseId = register();
    // 정산 완료면 거주 종료 시각도 있다(V1 CHECK)
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    respond(caseId, landlord, "r1", "ACK", null, 1).andExpect(jsonPath("$.code").value("SETTLED"));
  }

  @Test
  void 등록한_쪽이_공유를_그만둔_뒤_남긴_하자에는_상대가_답할_수_없다() throws Exception {
    // 세입자가 공유를 그만둔 뒤 등록한 하자는 "나만 보는 기록"이라 집주인에게 보이지 않는다(03 불변식 18)
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - interval '1 hour'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID caseId = register();

    respond(caseId, landlord, "r1", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(caseRow(caseId)).containsEntry("defect_ack", "PENDING");
    assertThat(countActions(caseId, "RESPOND_DEFECT")).isZero();
  }

  @Test
  void 공유를_그만두기_전에_남긴_하자에는_남은_사람이_답할_수_있다() throws Exception {
    UUID caseId = register();
    // 등록한 뒤(1초 뒤) 세입자가 공유를 그만뒀다. 그 전에 남긴 기록은 집주인에게 계속 보인다
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at + interval '1 second' FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        caseId,
        room);

    respond(caseId, landlord, "r1", "ACK", null, 1).andExpect(status().isOk());
  }

  @Test
  void 응답_사유에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    UUID caseId = register();

    respond(caseId, landlord, "r1", "DISPUTE", "제 번호 900101-1234567로 연락 주세요", 1)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(countActions(caseId, "RESPOND_DEFECT")).isZero();
  }

  // ---- 보충하고 다시 확인 요청 ----

  @Test
  void REP_2e_보류면_등록한_쪽이_보충하고_확인_요청을_다시_연다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(caseId, tenant, "s1", "{\"description\":\"밝은 데서 다시 찍었어요\"}")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RepairController.REPLAYED))
        .andExpect(jsonPath("$.id").value(caseId.toString()))
        .andExpect(jsonPath("$.defectAck").value("PENDING"));

    assertThat(caseRow(caseId)).containsEntry("defect_ack", "PENDING");
    Map<String, Object> supplement =
        jdbc.queryForMap("SELECT * FROM record.supplement WHERE repair_case_id = ?", caseId);
    assertThat(supplement)
        .containsEntry("room_record_id", room)
        .containsEntry("side", "TENANT")
        .containsEntry("description", "밝은 데서 다시 찍었어요");
    assertThat(supplement.get("payment_id")).isNull();
    assertThat(supplement.get("inspection_item_id")).isNull();

    Map<String, Object> action = action(caseId, "SUPPLEMENT");
    assertThat(action).containsEntry("actor_side", "TENANT");
    assertThat(action.get("created_at")).isEqualTo(supplement.get("created_at"));
    // 새 기록(보충 행)을 만드는 행동이라 그 행이 지문이다(안내서 3-3, #155)
    DigestInput expected =
        new DigestInput(
            "supplement",
            (UUID) supplement.get("id"),
            1,
            List.of(new DigestField("description", "밝은 데서 다시 찍었어요")));
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(expected));
    assertThat(waits.statusOf(defectRequest(caseId), RequestKind.DEFECT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 보충한_뒤에는_상대가_새로_고를_수_있다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());

    respond(caseId, landlord, "r2", "DISPUTE", "그래도 원래 있던 거예요", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISPUTE"));
  }

  @Test
  void REP_2e_근거_없이_보충하면_EVIDENCE_REQUIRED() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(caseId, tenant, "s1", "{\"description\":\"   \"}")
        .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    supplement(caseId, tenant, "s2", "{}").andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    assertThat(countRows("record.supplement", caseId)).isZero();
  }

  @Test
  void REP_2e_7일_안에_다시_보충하면_ASKED_RECENTLY() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());
    respond(caseId, landlord, "r2", "NEED_MORE", "아직 안 보여요", 1).andExpect(status().isOk());

    supplement(caseId, tenant, "s2", "{\"description\":\"한 번 더 찍었어요\"}")
        .andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    assertThat(countRows("record.supplement", caseId)).isEqualTo(1);
  }

  @Test
  void 확인_기다림이나_맞아요에는_보충할_수_없고_상대도_보충할_수_없다() throws Exception {
    UUID caseId = register();

    supplement(caseId, tenant, "s1", "{\"description\":\"더 찍었어요\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, landlord, "s2", "{\"description\":\"제가 찍었어요\"}")
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void 하자_확인_요청을_열_수_없는_단계면_보충할_수_없다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    waits.blockOpening(room, RequestKind.DEFECT_CONFIRM);

    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 거주가_끝나면_보충할_수_없다() throws Exception {
    // 가짜 WaitService에 막아 두지 않고 호실만 거주 종료로 바꾼다(03 K: 거주 종료 뒤 하자 확인 요청은 열 수 없음, #157 리뷰)
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}")
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(caseRow(caseId)).containsEntry("defect_ack", "NEED_MORE");
    assertThat(countRows("record.supplement", caseId)).isZero();
  }

  @Test
  void 응답한_판에_도장이_찍히고_보충해도_도장은_남는다() throws Exception {
    // 맞아요뿐 아니라 보류·이견도 도장이다(03 1-3)
    UUID caseId = register();
    assertThat(caseRow(caseId).get("confirmed_content_id")).isNull();

    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    Map<String, Object> answered = caseRow(caseId);
    assertThat(answered.get("confirmed_content_id")).isEqualTo(answered.get("current_content_id"));

    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());
    assertThat(caseRow(caseId))
        .containsEntry("defect_ack", "PENDING")
        .containsEntry("confirmed_content_id", answered.get("current_content_id"));
  }

  @Test
  void REP_10_저장된_행동을_처음부터_다시_계산하면_지금_상태와_같다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());
    respond(caseId, landlord, "r2", "ACK", null, 1).andExpect(status().isOk());

    RepairState folded =
        new TransactionTemplate(transactionManager)
            .execute(status ->
                Replay.fold(rules, actions.findByTarget(ActionTarget.repairCase(caseId))));

    Map<String, Object> row = caseRow(caseId);
    assertThat(folded.defectAck().name()).isEqualTo(row.get("defect_ack"));
    assertThat(folded.status().name()).isEqualTo(row.get("status"));
    assertThat(folded.moneyStatus().name()).isEqualTo(row.get("money_status"));
    assertThat(folded.registeredBy().name()).isEqualTo(row.get("created_by_side"));
  }

  @Test
  void 같은_키로_다시_보충하면_보충을_더_남기지_않는다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, tenant, "same", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());

    supplement(caseId, tenant, "same", "{\"description\":\"다시 찍었어요\"}")
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.defectAck").value("PENDING"));

    assertThat(countRows("record.supplement", caseId)).isEqualTo(1);
    assertThat(countActions(caseId, "SUPPLEMENT")).isEqualTo(1);
  }

  @Test
  void 아직_받지_않는_사진이_오거나_설명이_길면_VALIDATION_FAILED() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(caseId, tenant, "s1", "{\"uploadTickets\":[\"t1\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(caseId, tenant, "s2", "{\"description\":\"" + "가".repeat(2001) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    supplement(caseId, tenant, "s3", "{\"description\":\"" + "가".repeat(2000) + "\"}")
        .andExpect(status().isOk());
  }

  @Test
  void 보충_설명에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());

    supplement(caseId, tenant, "s1", "{\"description\":\"제 번호는 900101-1234567이에요\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
  }

  @Test
  void 보충_기록은_고치거나_지울_수_없다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "r1", "NEED_MORE", "사진이 흐려요", 1).andExpect(status().isOk());
    supplement(caseId, tenant, "s1", "{\"description\":\"다시 찍었어요\"}").andExpect(status().isOk());

    assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE record.supplement SET description = 'x' WHERE repair_case_id = ?", caseId))
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM record.supplement WHERE repair_case_id = ?", caseId))
        .hasMessageContaining("append-only");
  }

  // ---- 준비물 ----

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

  private ResultActions respond(
      UUID caseId, UUID memberId, String key, String answer, String reason, Integer seen)
      throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + (seen == null ? "" : ",\"seenVersionNo\":" + seen)
            + "}";
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions supplement(UUID caseId, UUID memberId, String key, String json)
      throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/supplement", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private DigestInput contentDigest(UUID caseId, String title, String description) {
    UUID contentId =
        jdbc.queryForObject(
            "SELECT id FROM record.repair_content WHERE repair_case_id = ? AND no = 1",
            UUID.class,
            caseId);
    return new DigestInput(
        "repair_content",
        contentId,
        1,
        List.of(
            new DigestField("room_part_id", part.toString()),
            new DigestField("title", title),
            new DigestField("description", description)));
  }

  private static RequestTarget defectRequest(UUID caseId) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, caseId);
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

  private long countRows(String table, UUID caseId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE repair_case_id = ?", Long.class, caseId);
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
