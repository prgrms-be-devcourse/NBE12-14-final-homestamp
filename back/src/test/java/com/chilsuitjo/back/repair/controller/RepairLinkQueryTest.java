package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.correction.rule.CorrectionStatus;
import com.chilsuitjo.back.global.common.AckState;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.repair.dto.RepairLinkView;
import com.chilsuitjo.back.repair.rule.CauseBasis;
import com.chilsuitjo.back.repair.rule.RepairStatus;
import com.chilsuitjo.back.repair.service.JpaRepairLinkQuery;
import com.chilsuitjo.back.repair.service.RepairLinkQuery;
import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
 * 링크용 수리 건 조회(#443, #303 B 답 7206508708). 수리 API로 진짜 수리 건·판·원인 설명·진행 상황·고친 내용과 행동 기록을 만들고 {@link
 * RepairLinkQuery}로 읽는다. 세입자가 하자를 등록하고 링크는 집주인 측이 받는다. 링크 무효·호실 상태는 link가 먼저 보므로 여기서는 보이는 범위만 본다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairLinkQueryTest {

  @Autowired private RepairLinkQuery query;
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
  void 진짜_구현이_쓰인다() {
    assertThat(query).isInstanceOf(JpaRepairLinkQuery.class);
  }

  @Test
  void 등록한_수리_건은_내용_상태_사진_대상_경과와_함께_보인다() throws Exception {
    UUID caseId = register();

    RepairLinkView seen = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.registeredBy()).isEqualTo(PartySide.TENANT);
    assertThat(seen.status()).isEqualTo(RepairStatus.NOT_STARTED);
    assertThat(seen.defectAck()).isEqualTo(AckState.PENDING);
    assertThat(seen.closeRequestedBy()).isNull();
    assertThat(seen.closedBy()).isNull();
    assertThat(seen.asOf()).isNull();
    assertThat(seen.partName()).isEqualTo("천장");
    assertThat(seen.title()).isEqualTo("천장 누수");
    assertThat(seen.description()).isEqualTo("비 오면 물이 떨어져요");
    assertThat(seen.photoTarget())
        .isEqualTo(new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, caseId));
    assertThat(seen.corrections()).isEmpty();
    assertThat(seen.causeNotes()).isEmpty();
    assertThat(seen.progressNotes()).isEmpty();
    assertThat(seen.history())
        .singleElement()
        .satisfies(line -> {
          assertThat(line.type()).isEqualTo(ActionType.REGISTER);
          assertThat(line.side()).isEqualTo(PartySide.TENANT);
          assertThat(line.targetKind()).isEqualTo(TargetKind.REPAIR_CASE);
          assertThat(line.targetId()).isNull();
        });
  }

  @Test
  void 진행_하자_인정과_종료_요청은_측으로_보인다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK").andExpect(status().isOk());
    act(caseId, tenant, "start").andExpect(status().isOk());
    act(caseId, tenant, "request-close").andExpect(status().isOk());

    RepairLinkView seen = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.defectAck()).isEqualTo(AckState.ACK);
    assertThat(seen.status()).isEqualTo(RepairStatus.CLOSE_WAIT);
    assertThat(seen.closeRequestedBy()).isEqualTo(PartySide.TENANT);
    assertThat(seen.history())
        .extracting(RepairLinkView.History::type)
        .containsExactly(
            ActionType.REGISTER,
            ActionType.RESPOND_DEFECT,
            ActionType.START_REPAIR,
            ActionType.REQUEST_CLOSE);
  }

  @Test
  void 원인_설명_진행_상황_고친_내용과_그_응답이_경과와_이어져_보인다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK").andExpect(status().isOk()); // 도장: 뒤의 고친 내용은 확인을 받는다
    UUID cause =
        created(
            causeNote(
                caseId,
                tenant,
                "{\"text\":\"배관 노후\",\"basis\":\"EXPERT\",\"expertName\":\"○○설비\"}"));
    respondCause(cause, landlord, "ACK").andExpect(status().isOk());
    UUID progress =
        created(progressNote(caseId, tenant, "{\"text\":\"업체 방문했어요\",\"asksCheck\":true}"));
    UUID correction =
        correctionId(correct(caseId, tenant, "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));

    RepairLinkView seen = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.title()).isEqualTo("천장 누수"); // 확인 전 고친 내용은 앞모습을 바꾸지 않는다
    assertThat(seen.causeNotes())
        .singleElement()
        .satisfies(note -> {
          assertThat(note.id()).isEqualTo(cause);
          assertThat(note.side()).isEqualTo(PartySide.TENANT);
          assertThat(note.text()).isEqualTo("배관 노후");
          assertThat(note.basis()).isEqualTo(CauseBasis.EXPERT);
          assertThat(note.answer()).isEqualTo(AckState.ACK);
          assertThat(note.photoTarget())
              .isEqualTo(new PhotoTarget.Ref(PhotoTargetType.CAUSE_NOTE, cause));
        });
    assertThat(seen.progressNotes())
        .singleElement()
        .satisfies(note -> {
          assertThat(note.id()).isEqualTo(progress);
          assertThat(note.text()).isEqualTo("업체 방문했어요");
          assertThat(note.asksCheck()).isTrue();
          assertThat(note.answer()).isEqualTo(AckState.PENDING);
          assertThat(note.photoTarget())
              .isEqualTo(new PhotoTarget.Ref(PhotoTargetType.PROGRESS_NOTE, progress));
        });
    assertThat(seen.corrections())
        .singleElement()
        .satisfies(c -> {
          assertThat(c.id()).isEqualTo(correction);
          assertThat(c.field()).isEqualTo("title");
          assertThat(c.newValue()).isEqualTo("천장 물샘");
          assertThat(c.requestedBy()).isEqualTo(PartySide.TENANT);
          assertThat(c.reason()).isEqualTo("오타");
          assertThat(c.status()).isEqualTo(CorrectionStatus.WAITING);
          assertThat(c.answer()).isEqualTo(AckState.PENDING);
          assertThat(c.photoTarget())
              .isEqualTo(new PhotoTarget.Ref(PhotoTargetType.CORRECTION_REQUEST, correction));
        });
    assertThat(seen.history())
        .filteredOn(line -> line.targetKind() == TargetKind.CAUSE_NOTE)
        .extracting(RepairLinkView.History::type, RepairLinkView.History::targetId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(ActionType.NOTE_CAUSE, cause),
            org.assertj.core.groups.Tuple.tuple(ActionType.RESPOND_CAUSE, cause));
    // 진행 상황 남기기는 수리 건이 대상이라(REP-6) 수리 건 줄로 남는다. 진행 상황이 대상인 행동은 그 응답뿐이다
    assertThat(seen.history())
        .filteredOn(line -> line.type() == ActionType.NOTE_PROGRESS)
        .singleElement()
        .satisfies(line -> {
          assertThat(line.targetKind()).isEqualTo(TargetKind.REPAIR_CASE);
          assertThat(line.targetId()).isNull();
        });
    assertThat(seen.history())
        .filteredOn(line -> line.targetKind() == TargetKind.CORRECTION_REQUEST)
        .extracting(RepairLinkView.History::targetId)
        .containsOnly(correction);
  }

  @Test
  void 고친_원인_설명은_지금_설명만_보이고_고치기_전_것은_경과에_남는다() throws Exception {
    UUID caseId = register();
    UUID first = created(causeNote(caseId, tenant, "{\"text\":\"배관 노후\",\"basis\":\"GUESS\"}"));
    UUID second =
        created(
            causeNote(
                caseId,
                tenant,
                "{\"text\":\"윗집 누수\",\"basis\":\"SEEN\",\"previousId\":\"" + first + "\"}"));

    RepairLinkView seen = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();

    assertThat(seen.causeNotes()).extracting(RepairLinkView.CauseNote::id).containsExactly(second);
    assertThat(seen.history())
        .filteredOn(line -> line.targetKind() == TargetKind.CAUSE_NOTE)
        .extracting(RepairLinkView.History::targetId)
        .containsExactly(first, second);
  }

  @Test
  void 불변식_18_고정_뒤_상대가_바꾼_상태와_남긴_기록은_보이지_않는다() throws Exception {
    // 집주인이 공유를 그만둔 뒤 세입자가 수리를 시작하고 원인 설명·진행 상황을 남긴다
    UUID caseId = register();
    Timestamp stoppedAt = caseCreatedAt(caseId);
    stopSharing(PartySide.LANDLORD, stoppedAt);
    act(caseId, tenant, "start").andExpect(status().isOk());
    created(causeNote(caseId, tenant, "{\"text\":\"배관 노후\",\"basis\":\"GUESS\"}"));
    created(progressNote(caseId, tenant, "{\"text\":\"업체 방문했어요\"}"));

    RepairLinkView landlordSees = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();

    assertThat(landlordSees.asOf()).isEqualTo(stoppedAt.toInstant());
    assertThat(landlordSees.status()).isEqualTo(RepairStatus.NOT_STARTED);
    assertThat(landlordSees.causeNotes()).isEmpty();
    assertThat(landlordSees.progressNotes()).isEmpty();
    assertThat(landlordSees.history())
        .extracting(RepairLinkView.History::type)
        .containsExactly(ActionType.REGISTER);
    // 양방향: 세입자도 같은 시각으로 고정되지만 자기 기록은 자기에게 보인다
    RepairLinkView tenantSees = query.forLink(room, caseId, PartySide.TENANT).orElseThrow();
    assertThat(tenantSees.asOf()).isEqualTo(stoppedAt.toInstant());
    assertThat(tenantSees.causeNotes()).hasSize(1);
    assertThat(tenantSees.progressNotes()).hasSize(1);
  }

  @Test
  void 불변식_18_고정_뒤_상대의_고친_내용_응답은_보이지_않는다() throws Exception {
    UUID caseId = register();
    respond(caseId, landlord, "ACK").andExpect(status().isOk());
    UUID correction =
        // 같이 확인한 뒤 고치면 이유가 필요하다(REASON_REQUIRED)
        correctionId(correct(caseId, tenant, "{\"field\":\"title\",\"newValue\":\"천장 물샘\"}", "오타"));
    Timestamp stoppedAt =
        jdbc.queryForObject(
            "SELECT created_at FROM state.correction_request WHERE id = ?",
            Timestamp.class,
            correction);
    stopSharing(PartySide.TENANT, stoppedAt);
    respondCorrection(correction, landlord, "ACK", 1).andExpect(status().isOk());

    RepairLinkView tenantSees = query.forLink(room, caseId, PartySide.TENANT).orElseThrow();

    assertThat(tenantSees.title()).isEqualTo("천장 누수");
    assertThat(tenantSees.corrections())
        .singleElement()
        .satisfies(c -> {
          assertThat(c.status()).isEqualTo(CorrectionStatus.WAITING);
          assertThat(c.answer()).isEqualTo(AckState.PENDING);
        });
    RepairLinkView landlordSees = query.forLink(room, caseId, PartySide.LANDLORD).orElseThrow();
    assertThat(landlordSees.title()).isEqualTo("천장 물샘");
    assertThat(landlordSees.corrections().getFirst().status()).isEqualTo(CorrectionStatus.ACCEPTED);
  }

  @Test
  void 공유를_그만둔_뒤_상대가_등록한_수리_건은_빈_값이고_등록한_측은_본다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - interval '1 hour'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID caseId = register();

    assertThat(query.forLink(room, caseId, PartySide.LANDLORD)).isEmpty();
    assertThat(query.forLink(room, caseId, PartySide.TENANT)).isPresent();
  }

  @Test
  void 다른_호실의_수리_건이나_없는_수리_건은_빈_값이다() throws Exception {
    UUID caseId = register();

    assertThat(query.forLink(insertRoom(), caseId, PartySide.LANDLORD)).isEmpty();
    assertThat(query.forLink(room, UUID.randomUUID(), PartySide.LANDLORD)).isEmpty();
  }

  @Test
  void 응답에_돈_업체_이름_호실_부위_회원_ID_행동_번호가_없다() {
    List<String> names =
        Stream.of(
                RepairLinkView.class,
                RepairLinkView.Correction.class,
                RepairLinkView.CauseNote.class,
                RepairLinkView.ProgressNote.class,
                RepairLinkView.History.class)
            .flatMap(type -> Arrays.stream(type.getRecordComponents()))
            .map(RecordComponent::getName)
            .toList();

    assertThat(names)
        .doesNotContain(
            "money",
            "moneyStatus",
            "expertName",
            "roomRecordId",
            "roomPartId",
            "memberId",
            "alias",
            "roomSeq",
            "hash",
            "availableActions");
  }

  // ---- 준비물 ----

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
    return idOf(body);
  }

  private ResultActions respond(UUID caseId, UUID memberId, String answer) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "res-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"" + answer + "\",\"seenVersionNo\":1}"));
  }

  private ResultActions act(UUID caseId, UUID memberId, String action) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/" + action, caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", action + "-" + UUID.randomUUID()));
  }

  private ResultActions causeNote(UUID caseId, UUID memberId, String json) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/cause-notes", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "cause-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions respondCause(UUID noteId, UUID memberId, String answer) throws Exception {
    return mvc.perform(
        post("/api/cause-notes/{id}/respond", noteId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "cres-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"" + answer + "\"}"));
  }

  private ResultActions progressNote(UUID caseId, UUID memberId, String json) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/progress-notes", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "prog-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions correct(UUID caseId, UUID memberId, String changes, String reason)
      throws Exception {
    String json =
        "{\"targetKind\":\"REPAIR_CASE\",\"targetId\":\""
            + caseId
            + "\",\"changes\":["
            + changes
            + "]"
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + "}";
    return mvc.perform(
        post("/api/corrections")
            .with(loginAs(memberId))
            .header("Idempotency-Key", "cor-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions respondCorrection(UUID correctionId, UUID memberId, String answer, int seen)
      throws Exception {
    return mvc.perform(
        post("/api/corrections/{id}/respond", correctionId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "cr-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\"" + answer + "\",\"seenVersionNo\":" + seen + "}"));
  }

  private static UUID created(ResultActions result) throws Exception {
    return idOf(result.andReturn().getResponse().getContentAsString());
  }

  private static UUID correctionId(ResultActions result) throws Exception {
    String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"correctionIds\":\\[\"([^\"]+)\".*", "$1"));
  }

  private static UUID idOf(String body) {
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
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

  /** 기본 목록(V7)에서 복사된 "방 › 천장"(수리 건 상세 테스트와 같음). */
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
