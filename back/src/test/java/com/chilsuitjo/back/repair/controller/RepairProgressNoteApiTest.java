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
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.FakePhotoService;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
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
 * 06 REP-6 진행 상황 묻기·남기기를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다(#219). 응답 요청·사진은 가짜({@code
 * FakeWaitService}·{@code FakePhotoService})라 상태만 확인한다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairProgressNoteApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private FakePhotoService photos;
  @Autowired private RoomAccessService access;
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
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
  }

  // ---- 진행 상황 묻기 ----

  @Test
  void 진행_상황을_물으면_상대에게_묻기_요청이_열리고_다음에_물을_수_있는_시각을_돌려준다() throws Exception {
    UUID caseId = started();

    ask(caseId, tenant)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(RepairController.REPLAYED))
        .andExpect(jsonPath("$.id").value(caseId.toString()))
        .andExpect(jsonPath("$.nextAskAt").isNotEmpty());

    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.WAITING);
    Map<String, Object> action = action(caseId, "ASK_PROGRESS");
    assertThat(action).containsEntry("actor_side", "TENANT");
    // 판을 보지 않는 행동이라 내용 지문이 없다(안내서 3-3)
    assertThat(action.get("content_digest")).isNull();
    assertThat(caseRow(caseId)).containsEntry("status", "IN_PROGRESS");
  }

  @Test
  void REP_6b_누가_물었든_7일_안에_다시_물으면_ASKED_RECENTLY() throws Exception {
    UUID caseId = started();
    ask(caseId, tenant).andExpect(status().isOk());

    ask(caseId, landlord)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    ask(caseId, tenant).andExpect(jsonPath("$.code").value("ASKED_RECENTLY"));
    assertThat(countActions(caseId, "ASK_PROGRESS")).isEqualTo(1);
  }

  @Test
  void 시작_전에도_물을_수_있지만_종료_확인_대기면_INVALID_STATE() throws Exception {
    UUID notStarted = register();
    ask(notStarted, landlord).andExpect(status().isOk());

    UUID waiting = started();
    act(waiting, tenant, "request-close", null).andExpect(status().isOk());
    ask(waiting, landlord).andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 거주가_끝나면_물을_수_없지만_진행_상황은_남길_수_있다() throws Exception {
    // 03 D "진행 상황 묻기: 거주 중", "남기기: 언제든". 거주 종료 뒤 남기기는 #113 답 전까지 지금 문서대로 허용
    UUID caseId = started();
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    ask(caseId, tenant).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    note(caseId, tenant, "{\"text\":\"부동산이 기다리래요\"}").andExpect(status().isCreated());
  }

  // ---- 진행 상황 남기기 ----

  @Test
  void 글만_남기면_진행_상황_한_줄과_내용_지문이_남는다() throws Exception {
    UUID caseId = started();

    UUID noteId =
        createdId(
            note(caseId, landlord, "{\"text\":\"윗집과 직접 이야기해 볼게요\"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(RepairController.REPLAYED)));

    Map<String, Object> row = noteRow(noteId);
    assertThat(row)
        .containsEntry("repair_case_id", caseId)
        .containsEntry("side", "LANDLORD")
        .containsEntry("text", "윗집과 직접 이야기해 볼게요")
        .containsEntry("asks_check", false);
    assertThat(row.get("answers_request_id")).isNull();
    Map<String, Object> action = action(caseId, "NOTE_PROGRESS");
    assertThat(action).containsEntry("actor_side", "LANDLORD");
    assertThat(action.get("content_digest")).isNotNull();
    // 행동과 진행 상황은 같은 시각이다(같은 키로 다시 온 요청이 이것으로 처음 ID를 찾는다)
    assertThat(action.get("created_at")).isEqualTo(row.get("created_at"));
  }

  @Test
  void 사진만_남기면_글은_빈_글자이고_사진은_진행_상황에_붙는다() throws Exception {
    UUID caseId = started();

    UUID noteId =
        createdId(
            note(caseId, tenant, "{\"uploadTickets\":[\"" + UUID.randomUUID() + "\"]}")
                .andExpect(status().isCreated()));

    assertThat(noteRow(noteId)).containsEntry("text", "");
    List<PhotoCard> cards = cardsOf(new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, noteId));
    assertThat(cards).hasSize(1);
    assertThat(cards.getFirst().stage()).isEqualTo(PhotoStage.PROGRESS);
    String payloadPhotos =
        jdbc.queryForObject(
            "SELECT payload ->> 'photos' FROM record.action WHERE repair_case_id = ? AND type ="
                + " 'NOTE_PROGRESS'",
            String.class,
            caseId);
    assertThat(payloadPhotos).contains(cards.getFirst().id().toString());
  }

  @Test
  void 처리_완료_뒤에도_진행_상황을_남길_수_있다() throws Exception {
    UUID caseId = started();
    act(caseId, tenant, "request-close", null).andExpect(status().isOk());
    act(caseId, landlord, "confirm-close", null).andExpect(status().isOk());

    note(caseId, tenant, "{\"text\":\"한 달 지나도 괜찮아요\"}").andExpect(status().isCreated());
    assertThat(caseRow(caseId)).containsEntry("status", "DONE");
  }

  @Test
  void 글도_사진도_없거나_입력이_잘못되면_남기지_않는다() throws Exception {
    UUID caseId = started();

    note(caseId, tenant, "{\"text\":\"  \"}")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    note(caseId, tenant, "{}").andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
    note(caseId, tenant, "{\"text\":\"제 번호 900101-1234567\"}")
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    note(caseId, tenant, "{\"text\":\"" + "가".repeat(2001) + "\"}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    note(caseId, tenant, "{\"text\":\"x\",\"uploadTickets\":[\"\"]}")
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(countActions(caseId, "NOTE_PROGRESS")).isZero();
    assertThat(noteCount(caseId)).isZero();
  }

  @Test
  void 공유를_그만둔_사람도_진행_상황을_남길_수_있지만_물을_수는_없다() throws Exception {
    // 03 B "그만둔 사람이 할 수 있는 것": 나만 보는 기록(진행 상황). 묻기는 상대에게 요청을 여는 일이라 막힌다
    UUID caseId = started();
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    note(caseId, tenant, "{\"text\":\"혼자 적어 둘게요\"}").andExpect(status().isCreated());
    ask(caseId, tenant).andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
  }

  // ---- REP-6c: 상대가 그 건에 무엇이든 남기면 물음이 닫힌다 ----

  @Test
  void REP_6c_상대가_진행_상황을_남기면_묻기_요청이_닫힌다() throws Exception {
    UUID caseId = started();
    ask(caseId, tenant).andExpect(status().isOk());

    // 물은 사람이 남긴 것은 답이 아니다
    note(caseId, tenant, "{\"text\":\"혹시 소식 있나요\"}").andExpect(status().isCreated());
    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.WAITING);

    note(caseId, landlord, "{\"text\":\"다음 주에 업체가 와요\"}").andExpect(status().isCreated());
    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void REP_6c_상대가_종료를_요청해도_묻기_요청이_닫힌다() throws Exception {
    UUID caseId = started();
    ask(caseId, tenant).andExpect(status().isOk());

    act(caseId, landlord, "request-close", null).andExpect(status().isOk());

    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void REP_6c_상대가_하자에_답해도_묻기_요청이_닫힌다() throws Exception {
    UUID caseId = register();
    ask(caseId, tenant).andExpect(status().isOk());

    act(caseId, landlord, "respond", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isOk());

    assertThat(waits.statusOf(caseRequest(caseId), RequestKind.PROGRESS_CHECK))
        .contains(RequestStatus.ANSWERED);
  }

  // ---- 상세의 버튼과 같은 키 ----

  @Test
  void 상세에_진행_상황_버튼이_보이고_물은_뒤에는_양쪽_모두_묻기가_빠진다() throws Exception {
    UUID caseId = started();

    // 등록한 세입자: 하자 버튼 없이 수리 진행 다음에 진행 상황 버튼이 온다
    detail(caseId, tenant)
        .andExpect(jsonPath("$.availableActions.length()").value(4))
        .andExpect(jsonPath("$.availableActions[0]").value("request-close"))
        .andExpect(jsonPath("$.availableActions[1]").value("ask-progress"))
        .andExpect(jsonPath("$.availableActions[2]").value("progress-notes"))
        .andExpect(jsonPath("$.availableActions[3]").value("cause-notes"));
    ask(caseId, tenant).andExpect(status().isOk());

    // 누가 물었든 한 건에 7일에 한 번이라 양쪽 모두 묻기가 빠진다
    detail(caseId, tenant)
        .andExpect(jsonPath("$.availableActions.length()").value(3))
        .andExpect(jsonPath("$.availableActions[1]").value("progress-notes"))
        .andExpect(jsonPath("$.availableActions[2]").value("cause-notes"));
    detail(caseId, landlord)
        .andExpect(jsonPath("$.availableActions.length()").value(4))
        .andExpect(jsonPath("$.availableActions[0]").value("respond"))
        .andExpect(jsonPath("$.availableActions[1]").value("request-close"))
        .andExpect(jsonPath("$.availableActions[2]").value("progress-notes"))
        .andExpect(jsonPath("$.availableActions[3]").value("cause-notes"));
  }

  @Test
  void 같은_키로_다시_보내면_더_남기지_않고_처음_만든_진행_상황을_돌려준다() throws Exception {
    UUID caseId = started();
    UUID first =
        createdId(
            noteWithKey(caseId, tenant, "same", "{\"text\":\"기다리는 중\"}")
                .andExpect(status().isCreated()));

    UUID again =
        createdId(
            noteWithKey(caseId, tenant, "same", "{\"text\":\"기다리는 중\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string(RepairController.REPLAYED, "true")));

    assertThat(again).isEqualTo(first);
    assertThat(noteCount(caseId)).isEqualTo(1);

    actWithKey(caseId, landlord, "ask-progress", "ask", null).andExpect(status().isOk());
    actWithKey(caseId, landlord, "ask-progress", "ask", null)
        .andExpect(status().isOk())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.nextAskAt").isNotEmpty());
    assertThat(countActions(caseId, "ASK_PROGRESS")).isEqualTo(1);
  }

  @Test
  void 없는_수리_건이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID caseId = started();

    ask(UUID.randomUUID(), tenant).andExpect(status().isNotFound());
    ask(caseId, member()).andExpect(status().isNotFound());
    note(caseId, member(), "{\"text\":\"x\"}").andExpect(status().isNotFound());
  }

  // ---- 준비물 ----

  private UUID started() throws Exception {
    UUID caseId = register();
    act(caseId, tenant, "start", null).andExpect(status().isOk());
    return caseId;
  }

  private ResultActions ask(UUID caseId, UUID memberId) throws Exception {
    return act(caseId, memberId, "ask-progress", null);
  }

  private ResultActions note(UUID caseId, UUID memberId, String json) throws Exception {
    return noteWithKey(caseId, memberId, "note-" + UUID.randomUUID(), json);
  }

  private ResultActions noteWithKey(UUID caseId, UUID memberId, String key, String json)
      throws Exception {
    return actWithKey(caseId, memberId, "progress-notes", key, json);
  }

  private ResultActions act(UUID caseId, UUID memberId, String action, String json)
      throws Exception {
    return actWithKey(caseId, memberId, action, action + "-" + UUID.randomUUID(), json);
  }

  private ResultActions actWithKey(
      UUID caseId, UUID memberId, String action, String key, String json) throws Exception {
    var request =
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key);
    if (json != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mvc.perform(request);
  }

  private ResultActions detail(UUID caseId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/repair-cases/{id}", caseId).with(loginAs(memberId)));
  }

  private static UUID createdId(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 세입자가 사진 한 장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register() throws Exception {
    return createdId(
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
            .andExpect(status().isCreated()));
  }

  /** 세입자가 보는 그 대상의 사진 카드(가짜 사진 창구). */
  private List<PhotoCard> cardsOf(PhotoTarget target) {
    RoomAccess viewer =
        new TransactionTemplate(transactionManager).execute(s -> access.require(room, tenant));
    return photos.cards(viewer, List.of(target)).get(target);
  }

  private static RequestTarget caseRequest(UUID caseId) {
    return RequestTarget.of(TargetKind.REPAIR_CASE, caseId);
  }

  private Map<String, Object> caseRow(UUID caseId) {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
  }

  private Map<String, Object> noteRow(UUID noteId) {
    return jdbc.queryForMap("SELECT * FROM record.progress_note WHERE id = ?", noteId);
  }

  private long noteCount(UUID caseId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.progress_note WHERE repair_case_id = ?", Long.class, caseId);
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
