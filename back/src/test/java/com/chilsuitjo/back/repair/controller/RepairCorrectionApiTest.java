package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.ClosedReason;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
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
 * 06 REP-7 수리 건 내용 고치기를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 사진은 가짜 사진 창구가 받는다.
 *
 * <p>세입자가 하자를 등록하고(판 #1 "천장 누수"), 도장이 필요한 시험은 집주인이 판 #1에 [맞아요]로 응답해 도장을 찍는다. 테스트마다 새 호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairCorrectionApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;
  private UUID caseId;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    part = part("천장");
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    caseId = register();
  }

  // ---- 도장 전 ----

  @Test
  void REP_7a_같이_확인하기_전에는_바로_바뀌고_응답_요청이_없다() throws Exception {
    correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("APPLIED"))
        .andExpect(jsonPath("$.correctionIds", hasSize(0)))
        .andExpect(jsonPath("$.message").value("고쳤어요"));

    Map<String, Object> head = head();
    Map<String, Object> current = content((UUID) head.get("current_content_id"));
    assertThat(current).containsEntry("no", 2).containsEntry("title", "천장 물샘");
    assertThat(current.get("room_part_id")).isEqualTo(part);
    assertThat(head.get("confirmed_content_id")).isNull();
    // 고친 이력이 남는다: 이전 판을 가리키고, 행동 대상은 수리 건이며 지문은 새 판
    assertThat(current.get("previous_id")).isEqualTo(contentIdOf(1));
    Map<String, Object> action = caseAction("CORRECT");
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(digestOf(2)));
    assertThat(count("state.correction_request")).isZero();
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM)).isEmpty();
    // 상대가 옛 판(#1)을 보고 답하면 VERSION_CONFLICT
    defectAck(landlord, 1).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void 원래_기록한_측이_아니면_고칠_수_없다() throws Exception {
    correct(landlord, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void 앞모습과_같은_값이면_SAME_VALUE이고_없는_칸은_VALIDATION_FAILED() throws Exception {
    correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 누수\"}", null)
        .andExpect(jsonPath("$.code").value("SAME_VALUE"));
    correct(tenant, "c2", "{\"field\":\"color\",\"newValue\":\"빨강\"}", null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    correct(tenant, "c3", "{\"field\":\"title\",\"newValue\":\"\"}", null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(contentCount()).isEqualTo(1);
  }

  @Test
  void 잘못된_요청은_pipeline_전에_VALIDATION_FAILED다() throws Exception {
    // 같은 칸 두 번
    correct(
            tenant,
            "v1",
            "{\"field\":\"title\",\"newValue\":\"가\"},{\"field\":\"title\",\"newValue\":\"나\"}",
            null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 칸이 없음
    correct(tenant, "v2", "", null).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 칸 이름이 비었음
    correct(tenant, "v3", "{\"field\":\" \",\"newValue\":\"가\"}", null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 사유가 2,000자를 넘음
    correct(tenant, "v4", "{\"field\":\"title\",\"newValue\":\"가\"}", "가".repeat(2001))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 대상 종류가 없거나 아직 고칠 수 없는 기록 종류(지불은 C가 구현)
    postCorrection(tenant, "v5", "{\"targetId\":\"" + caseId + "\",\"changes\":[]}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    postCorrection(
            tenant,
            "v6",
            "{\"targetKind\":\"PAYMENT\",\"targetId\":\""
                + caseId
                + "\",\"changes\":[{\"field\":\"amount\",\"newValue\":1}]}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    // 응답에 답이 없음, 없는 고친 내용
    mvc.perform(
            post("/api/corrections/{id}/respond", UUID.randomUUID())
                .with(loginAs(landlord))
                .header("Idempotency-Key", "v7-" + caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenVersionNo\":1}"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    respond(UUID.randomUUID(), landlord, "v8", "ACK", null, 1).andExpect(status().isNotFound());
    // 사유에 주민등록번호 형태
    correct(tenant, "v9", "{\"field\":\"title\",\"newValue\":\"가\"}", "900101-1234567")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(contentCount()).isEqualTo(1);
  }

  @Test
  void 부위를_고치면_같은_호실의_보이는_부위로_바뀐다() throws Exception {
    UUID wall = part("벽");
    correct(tenant, "c1", "{\"field\":\"roomPartId\",\"newValue\":\"" + wall + "\"}", null)
        .andExpect(jsonPath("$.result").value("APPLIED"));

    assertThat(content((UUID) head().get("current_content_id")).get("room_part_id"))
        .isEqualTo(wall);
  }

  @Test
  void 숨긴_부위나_꺼_둔_설비로는_고칠_수_없다() throws Exception {
    UUID hidden = part("바닥");
    UUID off = part("창문·창틀");
    jdbc.update("UPDATE state.room_part SET hidden_at = CURRENT_TIMESTAMP WHERE id = ?", hidden);
    jdbc.update("UPDATE state.room_part SET enabled = false WHERE id = ?", off);

    correct(tenant, "c1", "{\"field\":\"roomPartId\",\"newValue\":\"" + hidden + "\"}", null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    correct(tenant, "c2", "{\"field\":\"roomPartId\",\"newValue\":\"" + off + "\"}", null)
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(contentCount()).isEqualTo(1);
  }

  // ---- 도장 뒤 ----

  @Test
  void REP_7c_같이_확인한_뒤_고치면_앞모습은_그대로이고_확인_요청이_간다() throws Exception {
    stamp();

    UUID correctionId =
        firstId(
            correct(tenant, "c1", "{\"field\":\"description\",\"newValue\":\"물이 새요\"}", "오타")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REQUESTED"))
                .andExpect(jsonPath("$.correctionIds", hasSize(1))));

    assertThat(contentCount()).isEqualTo(1);
    assertThat(head().get("current_content_id")).isEqualTo(contentIdOf(1));
    Map<String, Object> row = correction(correctionId);
    assertThat(row)
        .containsEntry("field", "description")
        .containsEntry("status", "WAITING")
        .containsEntry("requested_by_side", "TENANT")
        .containsEntry("reason", "오타")
        .containsEntry("repair_case_id", caseId)
        .containsEntry("base_version_id", contentIdOf(1));
    assertThat(row.get("new_value").toString()).isEqualTo("\"물이 새요\"");
    Map<String, Object> action = correctionAction(correctionId, "CORRECT");
    assertThat(action).containsEntry("reason", "오타");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 같이_확인한_뒤_사유_없이_고치면_REASON_REQUIRED이고_아무것도_남지_않는다() throws Exception {
    stamp();

    correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

    assertThat(count("state.correction_request")).isZero();
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM)).isEmpty();
  }

  @Test
  void REP_7h_여러_칸을_고쳐도_확인_요청은_하나이고_같은_칸을_다시_고치면_이전_것은_대신된다() throws Exception {
    stamp();

    List<UUID> first =
        ids(
            correct(
                    tenant,
                    "c1",
                    "{\"field\":\"title\",\"newValue\":\"천장 물샘\"},"
                        + "{\"field\":\"description\",\"newValue\":\"물이 새요\"}",
                    "더 정확하게")
                .andExpect(jsonPath("$.correctionIds", hasSize(2))));
    UUID again =
        firstId(correct(tenant, "c2", "{\"field\":\"title\",\"newValue\":\"천장 누수 심함\"}", "다시"));

    assertThat(correction(first.get(0))).containsEntry("status", "SUPERSEDED");
    assertThat(correction(first.get(1))).containsEntry("status", "WAITING");
    assertThat(correction(again)).containsEntry("status", "WAITING");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void REP_7e_7i_하나만_확인하면_그_칸만_앞모습이_되고_모두_확인하면_요청이_닫힌다() throws Exception {
    stamp();
    List<UUID> ids =
        ids(
            correct(
                tenant,
                "c1",
                "{\"field\":\"title\",\"newValue\":\"천장 물샘\"},"
                    + "{\"field\":\"description\",\"newValue\":\"물이 새요\"}",
                "더 정확하게"));

    respond(ids.get(0), landlord, "r1", "ACK", null, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetId").value(ids.get(0).toString()))
        .andExpect(jsonPath("$.state").value("ACK"));

    Map<String, Object> head = head();
    Map<String, Object> second = content((UUID) head.get("current_content_id"));
    assertThat(head.get("confirmed_content_id")).isEqualTo(head.get("current_content_id"));
    assertThat(second).containsEntry("no", 2).containsEntry("title", "천장 물샘");
    assertThat(second.get("description")).isNull();
    assertThat(correction(ids.get(0))).containsEntry("status", "ACCEPTED");
    assertThat(correction(ids.get(1))).containsEntry("status", "WAITING");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);

    // 앞모습이 #2가 됐으니 #2를 보고 답한다
    respond(ids.get(1), landlord, "r2", "ACK", null, 2).andExpect(status().isOk());

    Map<String, Object> third = content((UUID) head().get("current_content_id"));
    assertThat(third)
        .containsEntry("no", 3)
        .containsEntry("title", "천장 물샘")
        .containsEntry("description", "물이 새요");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void REP_7d_확인할_때_본_판_번호와_그_판의_지문이_응답_행동에_남는다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));

    respond(id, landlord, "r1", "ACK", null, 1).andExpect(status().isOk());

    Map<String, Object> action = correctionAction(id, "RESPOND_CORRECTION");
    assertThat(action).containsEntry("seen_version_no", 1).containsEntry("actor_side", "LANDLORD");
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(digestOf(1)));
  }

  @Test
  void REP_7j_보지_않은_고친_내용이나_지난_앞모습에는_확인할_수_없다() throws Exception {
    stamp();
    UUID seen =
        firstId(correct(tenant, "c1", "{\"field\":\"description\",\"newValue\":\"물이 셈\"}", "오타"));
    firstId(correct(tenant, "c2", "{\"field\":\"description\",\"newValue\":\"물이 새요\"}", "다시"));

    respond(seen, landlord, "r1", "ACK", null, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

    UUID title =
        firstId(correct(tenant, "c3", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));
    respond(title, landlord, "r2", "ACK", null, 2)
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    assertThat(correction(title)).containsEntry("status", "WAITING");
  }

  @Test
  void REP_7m_보류와_이견은_구분해_남고_앞모습은_그대로다() throws Exception {
    stamp();
    List<UUID> ids =
        ids(
            correct(
                tenant,
                "c1",
                "{\"field\":\"title\",\"newValue\":\"천장 물샘\"},"
                    + "{\"field\":\"description\",\"newValue\":\"물이 새요\"}",
                "더 정확하게"));

    respond(ids.get(0), landlord, "r0", "NEED_MORE", null, 1)
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    respond(ids.get(0), landlord, "r1", "NEED_MORE", "사진이 더 필요해요", 1)
        .andExpect(jsonPath("$.state").value("NEED_MORE"));
    respond(ids.get(1), landlord, "r2", "DISPUTE", "물이 새지 않아요", 1)
        .andExpect(jsonPath("$.state").value("DISPUTE"));

    assertThat(contentCount()).isEqualTo(1);
    assertThat(correction(ids.get(0))).containsEntry("status", "REJECTED");
    assertThat(correctionAction(ids.get(0), "RESPOND_CORRECTION"))
        .containsEntry("reason", "사진이 더 필요해요");
    assertThat(correctionAction(ids.get(1), "RESPOND_CORRECTION").get("payload").toString())
        .contains("DISPUTE");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.ANSWERED);

    // 보류 ↔ 이견은 바로 바꾸지 않고, 맞아요로는 바꿀 수 있다(03 G "나중에 답 바꾸기")
    respond(ids.get(0), landlord, "r3", "DISPUTE", "다시 보니", 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    respond(ids.get(0), landlord, "r4", "ACK", null, 1).andExpect(jsonPath("$.state").value("ACK"));
    assertThat(content((UUID) head().get("current_content_id"))).containsEntry("title", "천장 물샘");
  }

  @Test
  void 고친_측은_자기_고친_내용에_답하지_못한다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));

    respond(id, tenant, "r1", "ACK", null, 1)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void REP_7k_양측_확인으로_끝난_수리_건은_고치지_않는다() throws Exception {
    stamp();
    jdbc.update(
        "UPDATE state.repair_case SET status = 'DONE', closed_by = 'BOTH' WHERE id = ?", caseId);

    correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLOSED_BY_BOTH"));
  }

  @Test
  void 모두_확인하면_고친_내용이_모두_앞모습이_되고_확인_요청이_닫힌다() throws Exception {
    stamp();
    List<UUID> ids = twoCorrections();

    acceptAll(landlord, "a1", 1, ids)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correctionIds", hasSize(2)));

    Map<String, Object> front = content((UUID) head().get("confirmed_content_id"));
    assertThat(front)
        .containsEntry("no", 3)
        .containsEntry("title", "천장 물샘")
        .containsEntry("description", "물이 새요");
    assertThat(correction(ids.get(0))).containsEntry("status", "ACCEPTED");
    assertThat(correction(ids.get(1))).containsEntry("status", "ACCEPTED");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.ANSWERED);
    // 고친 내용마다 응답 행동이 남고, 본 번호와 지문은 화면이 본 앞모습(#1)이다
    for (UUID id : ids) {
      Map<String, Object> action = correctionAction(id, "RESPOND_CORRECTION");
      assertThat(action).containsEntry("seen_version_no", 1);
      assertThat((byte[]) action.get("content_digest"))
          .isEqualTo(ContentDigest.compute(digestOf(1)));
    }

    acceptAll(landlord, "a1", 1, ids)
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"));
  }

  @Test
  void 모두_확인은_본_목록이나_본_번호가_다르면_아무것도_바꾸지_않는다() throws Exception {
    stamp();
    List<UUID> ids = twoCorrections();

    // 화면이 하나만 봤다(그사이 하나 더 고침)
    acceptAll(landlord, "a1", 1, List.of(ids.get(0)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    acceptAll(landlord, "a2", 2, ids)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    acceptAll(tenant, "a3", 1, ids).andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    acceptAll(landlord, "a4", 1, List.of(ids.get(0), ids.get(0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(correction(ids.get(0))).containsEntry("status", "WAITING");
    assertThat(correction(ids.get(1))).containsEntry("status", "WAITING");
    assertThat(contentCount()).isEqualTo(1);
  }

  // ---- 상세 ----

  @Test
  void 상세에_고친_내용과_측마다_누를_수_있는_버튼이_보인다() throws Exception {
    stamp();
    UUID other = part("벽");
    List<UUID> ids =
        ids(
            correct(
                tenant,
                "c1",
                "{\"field\":\"roomPartId\",\"newValue\":\""
                    + other
                    + "\"},"
                    + "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}",
                "더 정확하게"));
    respond(ids.get(1), landlord, "r1", "NEED_MORE", "사진이 더 필요해요", 1).andExpect(status().isOk());

    // 앞모습은 그대로이고 고친 내용은 만든 순서로 온다
    detail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.no").value(1))
        .andExpect(jsonPath("$.corrections", hasSize(2)))
        .andExpect(jsonPath("$.corrections[0].field").value("roomPartId"))
        .andExpect(jsonPath("$.corrections[0].newValue").value(other.toString()))
        .andExpect(jsonPath("$.corrections[0].newPartName").value("벽"))
        .andExpect(jsonPath("$.corrections[0].status").value("WAITING"))
        .andExpect(jsonPath("$.corrections[0].answer").value("PENDING"))
        .andExpect(jsonPath("$.corrections[0].requestedBy").value("TENANT"))
        .andExpect(jsonPath("$.corrections[0].reason").value("더 정확하게"))
        .andExpect(jsonPath("$.corrections[0].availableActions").value(contains("respond")))
        .andExpect(jsonPath("$.corrections[1].status").value("REJECTED"))
        .andExpect(jsonPath("$.corrections[1].answer").value("NEED_MORE"))
        .andExpect(jsonPath("$.corrections[1].answerReason").value("사진이 더 필요해요"))
        .andExpect(jsonPath("$.corrections[1].newPartName").doesNotExist())
        // 보류는 맞아요로 바꿀 수 있다
        .andExpect(jsonPath("$.corrections[1].availableActions").value(contains("respond")))
        .andExpect(jsonPath("$.availableActions", hasItem("accept-all")));
    detail(tenant)
        .andExpect(jsonPath("$.corrections[0].availableActions").value(contains("withdraw")))
        .andExpect(jsonPath("$.corrections[1].availableActions", hasSize(0)))
        .andExpect(jsonPath("$.availableActions", not(hasItem("accept-all"))));
  }

  @Test
  void 공유를_그만둔_뒤에는_그_뒤_상대가_고친_것과_내_고친_것에_대한_상대_응답이_보이지_않는다() throws Exception {
    stamp();
    UUID mine =
        firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));
    // 세입자가 공유를 그만둠(그 시각 = 지금). 그 뒤 집주인이 세입자의 고친 내용에 답한다
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        java.sql.Timestamp.from(java.time.Instant.now()),
        room);
    respond(mine, landlord, "r1", "NEED_MORE", "사진이 더 필요해요", 1).andExpect(status().isOk());

    detail(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.corrections", hasSize(1)))
        .andExpect(jsonPath("$.corrections[0].status").value("WAITING"))
        .andExpect(jsonPath("$.corrections[0].answer").value("PENDING"))
        .andExpect(jsonPath("$.corrections[0].answerReason").doesNotExist());
    // 남은 쪽은 자기가 남긴 응답을 본다
    detail(landlord)
        .andExpect(jsonPath("$.corrections[0].answer").value("NEED_MORE"))
        .andExpect(jsonPath("$.corrections[0].answerReason").value("사진이 더 필요해요"));
  }

  @Test
  void 도장_전에_고친_수리_건도_공유를_그만둔_뒤_상세를_볼_수_있다() throws Exception {
    correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", null)
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        java.sql.Timestamp.from(java.time.Instant.now()),
        room);

    detail(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.no").value(2))
        .andExpect(jsonPath("$.content.title").value("천장 물샘"));
  }

  @Test
  void 공유를_그만둔_뒤의_앞모습은_그_시각까지_확인된_판이다() throws Exception {
    stamp();
    List<UUID> ids = twoCorrections();
    respond(ids.get(0), landlord, "r1", "ACK", null, 1).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        java.sql.Timestamp.from(java.time.Instant.now()),
        room);
    // 그 뒤 집주인이 남은 하나도 확인한다(판 #3)
    respond(ids.get(1), landlord, "r2", "ACK", null, 2).andExpect(status().isOk());

    detail(tenant)
        .andExpect(jsonPath("$.content.no").value(2))
        .andExpect(jsonPath("$.content.title").value("천장 물샘"))
        .andExpect(jsonPath("$.content.description").doesNotExist());
    detail(landlord).andExpect(jsonPath("$.content.no").value(3));
  }

  @Test
  void REP_7l_양측_종료가_되면_확인_전_고친_내용과_그_확인_요청이_닫힌다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));
    progress(tenant, "start", null).andExpect(status().isOk());
    progress(tenant, "request-close", null).andExpect(status().isOk());

    progress(landlord, "confirm-close", null).andExpect(status().isOk());

    assertThat(correction(id))
        .containsEntry("status", "CLOSED")
        .containsEntry("closed_reason", "CLOSED_BY_BOTH");
    assertThat(correction(id).get("closed_at")).isNotNull();
    assertThat(waits.closedReasonOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(ClosedReason.CLOSED_BY_BOTH);
    // 앞모습은 그대로이고, 닫힌 고친 내용에 답하면 CLOSED_BY_BOTH
    assertThat(content((UUID) head().get("confirmed_content_id"))).containsEntry("no", 1);
    respond(id, landlord, "r1", "ACK", null, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLOSED_BY_BOTH"));
  }

  @Test
  void 아직_안_됐어요면_고친_내용은_그대로_기다린다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));
    progress(tenant, "start", null).andExpect(status().isOk());
    progress(tenant, "request-close", null).andExpect(status().isOk());

    progress(landlord, "not-yet", "{\"reason\":\"아직 물이 떨어져요\"}").andExpect(status().isOk());

    assertThat(correction(id)).containsEntry("status", "WAITING");
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 고친_측이_거두면_답할_수_없고_상대는_거둘_수_없다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));

    withdraw(id, landlord, "w0").andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
    withdraw(id, tenant, "w1").andExpect(status().isNoContent());
    withdraw(id, tenant, "w1")
        .andExpect(status().isNoContent())
        .andExpect(header().string(RepairController.REPLAYED, "true"));

    assertThat(correction(id)).containsEntry("status", "WITHDRAWN");
    respond(id, landlord, "r1", "ACK", null, 1)
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    // 기다리는 고친 내용이 남지 않아 확인 요청도 거둠으로 닫힌다(#258 리뷰)
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WITHDRAWN);
  }

  @Test
  void 기다리는_고친_내용이_남아_있으면_거둬도_확인_요청은_열려_있다() throws Exception {
    stamp();
    List<UUID> ids =
        ids(
            correct(
                tenant,
                "c1",
                "{\"field\":\"title\",\"newValue\":\"천장 물샘\"},"
                    + "{\"field\":\"description\",\"newValue\":\"물이 새요\"}",
                "더 정확하게"));

    withdraw(ids.get(0), tenant, "w1").andExpect(status().isNoContent());
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.WAITING);

    // 남은 것에 상대가 답하면 답함으로 닫힌다
    respond(ids.get(1), landlord, "r1", "ACK", null, 1).andExpect(status().isOk());
    assertThat(waits.statusOf(caseRequest(), RequestKind.CORRECTION_CONFIRM))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려준다() throws Exception {
    stamp();
    UUID id = firstId(correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));

    UUID again =
        firstId(
            correct(tenant, "c1", "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타")
                .andExpect(header().string(RepairController.REPLAYED, "true")));

    assertThat(again).isEqualTo(id);
    assertThat(count("state.correction_request")).isEqualTo(1);
  }

  // ---- 도우미 ----

  /** 집주인이 판 #1에 [맞아요]로 답해 도장을 찍는다(03 1-3). */
  private void stamp() throws Exception {
    defectAck(landlord, 1).andExpect(status().isOk());
  }

  private ResultActions defectAck(UUID memberId, int seen) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "ack-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"ACK\",\"seenVersionNo\":" + seen + "}"));
  }

  private ResultActions correct(UUID memberId, String key, String changes, String reason)
      throws Exception {
    String json =
        "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
            + caseId
            + "\",\"changes\":["
            + changes
            + "]"
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return postCorrection(memberId, key, json);
  }

  private ResultActions postCorrection(UUID memberId, String key, String json) throws Exception {
    return mvc.perform(
        post("/api/corrections")
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions respond(
      UUID correctionId, UUID memberId, String key, String answer, String reason, int seen)
      throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\",\"seenVersionNo\":"
            + seen
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return mvc.perform(
        post("/api/corrections/{id}/respond", correctionId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions withdraw(UUID correctionId, UUID memberId, String key) throws Exception {
    return mvc.perform(
        post("/api/corrections/{id}/withdraw", correctionId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId));
  }

  /** 도장 뒤 제목·설명을 함께 고친다. 칸마다 고친 내용 하나. */
  private List<UUID> twoCorrections() throws Exception {
    return ids(
        correct(
            tenant,
            "c1",
            "{\"field\":\"title\",\"newValue\":\"천장 물샘\"},"
                + "{\"field\":\"description\",\"newValue\":\"물이 새요\"}",
            "더 정확하게"));
  }

  private ResultActions detail(UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)));
  }

  private ResultActions acceptAll(UUID memberId, String key, int seen, List<UUID> ids)
      throws Exception {
    String list = String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList());
    String json =
        "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
            + caseId
            + "\",\"seenVersionNo\":"
            + seen
            + ",\"correctionIds\":["
            + list
            + "]}";
    return mvc.perform(
        post("/api/corrections/accept-all")
            .with(loginAs(memberId))
            .header("Idempotency-Key", key + "-" + caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /** 수리 진행 행동(시작·종료 요청·종료 확인·아직 안 됐어요). */
  private ResultActions progress(UUID memberId, String action, String json) throws Exception {
    var request =
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID());
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(request);
  }

  private static List<UUID> ids(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    String list = body.replaceAll(".*\"correctionIds\":\\[([^\\]]*)\\].*", "$1");
    return java.util.Arrays.stream(list.split(","))
        .map(s -> s.replace("\"", "").trim())
        .filter(s -> !s.isEmpty())
        .map(UUID::fromString)
        .toList();
  }

  private static UUID firstId(ResultActions result) throws Exception {
    return ids(result).getFirst();
  }

  private RequestTarget caseRequest() {
    return RequestTarget.of(TargetKind.REPAIR_CASE, caseId);
  }

  private Map<String, Object> head() {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  private Map<String, Object> content(UUID contentId) {
    return jdbc.queryForMap("SELECT * FROM record.repair_content WHERE id = ?", contentId);
  }

  private UUID contentIdOf(int no) {
    return jdbc.queryForObject(
        "SELECT id FROM record.repair_content WHERE repair_case_id = ? AND no = ?",
        UUID.class,
        caseId,
        no);
  }

  private long contentCount() {
    return count("record.repair_content");
  }

  private DigestInput digestOf(int no) {
    Map<String, Object> row = content(contentIdOf(no));
    return new DigestInput(
        "repair_content",
        (UUID) row.get("id"),
        no,
        List.of(
            new DigestField("room_part_id", row.get("room_part_id").toString()),
            new DigestField("title", (String) row.get("title")),
            new DigestField("description", (String) row.get("description"))));
  }

  private Map<String, Object> correction(UUID id) {
    return jdbc.queryForMap("SELECT * FROM state.correction_request WHERE id = ?", id);
  }

  private Map<String, Object> caseAction(String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE repair_case_id = ? AND type = ?", caseId, type);
  }

  private Map<String, Object> correctionAction(UUID correctionId, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE correction_request_id = ? AND type = ?"
            + " ORDER BY room_seq DESC LIMIT 1",
        correctionId,
        type);
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE repair_case_id = ?", Long.class, caseId);
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
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
                            + "\",\"title\":\"천장 누수\",\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
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

  /** 기본 목록(V7)에서 복사된 "방 › 이름" 부위. */
  private UUID part(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '방' AND t.name = ?
        """,
        id,
        room,
        name);
    return id;
  }
}
