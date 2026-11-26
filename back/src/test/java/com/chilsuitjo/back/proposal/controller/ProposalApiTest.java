package com.chilsuitjo.back.proposal.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.OpenRequestCommand;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import com.chilsuitjo.back.payment.service.FakePaymentService;
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
 * 06 MONEY-3 동의({@code POST /api/proposals/{id}/agree})를 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워
 * 본다. 응답 요청은 가짜({@code FakeWaitService})라 상태만 확인하고, 기준 금액은 가짜 지불 창구({@code FakePaymentService})에 적어
 * 둔다.
 *
 * <p>답할 상태(지난 안·합의 뒤 바꾸자는 안 등)를 바로 만들려고 제안·줄·head는 SQL로 넣는다. API로 제안하고 동의까지 잇는 흐름은 {@code
 * RepairProposalApiTest}가 본다. pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을
 * 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProposalApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private FakePaymentService payments;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID repair;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    repair = insertRepair();
  }

  @Test
  void 상대가_동의하면_합의되고_행동과_응답_요청이_남는다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 82_000);
    waits.open(
        new OpenRequestCommand(room, RequestKind.PROPOSAL, agendaRequest(), PartySide.LANDLORD));

    agree(proposal, tenant, "a1")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(ProposalController.REPLAYED))
        .andExpect(jsonPath("$.proposalId").value(proposal.toString()))
        .andExpect(jsonPath("$.replayed").value(false));

    assertThat(caseRow())
        .containsEntry("money_status", "AGREED")
        .containsEntry("agreed_proposal_id", proposal);
    assertThat(caseRow().get("current_proposal_id")).isNull();
    assertThat(action(proposal, "AGREE"))
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT")
        .containsEntry("room_record_id", room);
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 같은_키로_다시_보내면_행동을_더_남기지_않고_재요청으로_돌려준다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 82_000);
    agree(proposal, tenant, "same").andExpect(status().isOk());

    agree(proposal, tenant, "same")
        .andExpect(status().isOk())
        .andExpect(header().string(ProposalController.REPLAYED, "true"))
        .andExpect(jsonPath("$.proposalId").value(proposal.toString()))
        .andExpect(jsonPath("$.replayed").value(true));

    assertThat(countActions(proposal, "AGREE")).isEqualTo(1);
  }

  @Test
  void MONEY_3c_내_제안에는_내가_동의할_수_없다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 82_000);

    agree(proposal, landlord, "a1")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWN_PROPOSAL"));

    assertThat(caseRow()).containsEntry("money_status", "PENDING");
    assertThat(countActions(proposal, "AGREE")).isZero();
  }

  @Test
  void MONEY_3a_나눈_금액의_합이_전체_금액과_다르면_동의할_수_없다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 92_000);

    agree(proposal, tenant, "a1")
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("SUM_MISMATCH"));

    assertThat(caseRow()).containsEntry("money_status", "PENDING");
    assertThat(countActions(proposal, "AGREE")).isZero();
  }

  @Test
  void 지난_제안에_동의하면_NOT_CURRENT_PROPOSAL() throws Exception {
    UUID older = proposal(PartySide.LANDLORD, 82_000);
    UUID current = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 82_000);

    agree(older, tenant, "a1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NOT_CURRENT_PROPOSAL"));

    assertThat(caseRow()).containsEntry("current_proposal_id", current);
  }

  @Test
  void 없는_제안이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID proposal = landlordProposes(82_000);
    payments.seedConfirmedTotal(room, repair, 82_000);

    agree(UUID.randomUUID(), tenant, "a1")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    agree(proposal, member(), "a2")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(caseRow()).containsEntry("money_status", "PENDING");
  }

  @Test
  void Idempotency_Key가_없으면_400() throws Exception {
    UUID proposal = landlordProposes(82_000);

    mvc.perform(post("/api/proposals/{id}/agree", proposal).with(loginAs(tenant)))
        .andExpect(status().isBadRequest());

    assertThat(countActions(proposal, "AGREE")).isZero();
  }

  // ---- 이견 ----

  @Test
  void 상대가_사유와_함께_이견을_남기면_이견이_되고_사유는_행동에_남는다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    waits.open(
        new OpenRequestCommand(room, RequestKind.PROPOSAL, agendaRequest(), PartySide.LANDLORD));

    dispute(proposal, tenant, "d1", "제 잘못이 아니에요")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(ProposalController.REPLAYED))
        .andExpect(jsonPath("$.proposalId").value(proposal.toString()))
        .andExpect(jsonPath("$.replayed").value(false));

    assertThat(caseRow()).containsEntry("money_status", "DISPUTE");
    assertThat(caseRow().get("current_proposal_id")).isNull();
    assertThat(caseRow().get("agreed_proposal_id")).isNull();
    assertThat(action(proposal, "DISPUTE_PROPOSAL"))
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT")
        .containsEntry("reason", "제 잘못이 아니에요");
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 합의_뒤_바꾸자는_안에_이견을_남기면_합의는_그대로다() throws Exception {
    UUID agreed = proposal(PartySide.LANDLORD, 82_000);
    UUID change = proposal(PartySide.LANDLORD, 82_000);
    jdbc.update(
        "UPDATE state.repair_case SET money_status = 'AGREED', current_proposal_id = ?,"
            + " agreed_proposal_id = ? WHERE id = ?",
        change,
        agreed,
        repair);

    dispute(change, tenant, "d1", "처음 합의대로 해요").andExpect(status().isOk());

    assertThat(caseRow())
        .containsEntry("money_status", "AGREED")
        .containsEntry("agreed_proposal_id", agreed);
    assertThat(caseRow().get("current_proposal_id")).isNull();
  }

  @Test
  void 이견에_사유가_없거나_공백뿐이면_REASON_REQUIRED() throws Exception {
    UUID proposal = landlordProposes(82_000);

    dispute(proposal, tenant, "d1", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    dispute(proposal, tenant, "d2", "   ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    // 본문 자체가 없어도 본문 읽기 오류(VALIDATION_FAILED)가 아니라 사유 없음이다(RepairController.notYet과 같음)
    mvc.perform(
            post("/api/proposals/{id}/dispute", proposal)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "d3"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

    assertThat(caseRow()).containsEntry("money_status", "PENDING");
    assertThat(countActions(proposal, "DISPUTE_PROPOSAL")).isZero();
  }

  @Test
  void 내_제안에는_내가_이견을_남길_수_없다() throws Exception {
    UUID proposal = landlordProposes(82_000);

    dispute(proposal, landlord, "d1", "다시 생각해 보니")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWN_PROPOSAL"));

    assertThat(caseRow()).containsEntry("money_status", "PENDING");
  }

  @Test
  void 이견_사유에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    UUID proposal = landlordProposes(82_000);

    dispute(proposal, tenant, "d1", "제 번호 900101-1234567로 연락 주세요")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(countActions(proposal, "DISPUTE_PROPOSAL")).isZero();
  }

  @Test
  void 같은_키로_이견을_다시_보내면_행동을_더_남기지_않는다() throws Exception {
    UUID proposal = landlordProposes(82_000);
    dispute(proposal, tenant, "same", "달라요").andExpect(status().isOk());

    dispute(proposal, tenant, "same", "달라요")
        .andExpect(status().isOk())
        .andExpect(header().string(ProposalController.REPLAYED, "true"))
        .andExpect(jsonPath("$.replayed").value(true));

    assertThat(countActions(proposal, "DISPUTE_PROPOSAL")).isEqualTo(1);
  }

  private ResultActions agree(UUID proposal, UUID memberId, String key) throws Exception {
    return mvc.perform(
        post("/api/proposals/{id}/agree", proposal)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key));
  }

  private ResultActions dispute(UUID proposal, UUID memberId, String key, String reason)
      throws Exception {
    return mvc.perform(
        post("/api/proposals/{id}/dispute", proposal)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}"));
  }

  /** 집주인이 전부 내는 안을 내고 합의 대기가 된 모양. head가 이 안을 가리킨다. */
  private UUID landlordProposes(long amount) {
    UUID proposal = proposal(PartySide.LANDLORD, amount);
    jdbc.update(
        "UPDATE state.repair_case SET money_status = 'PENDING', current_proposal_id = ?"
            + " WHERE id = ?",
        proposal,
        repair);
    return proposal;
  }

  private UUID proposal(PartySide side, long amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO record.proposal
          (id, room_record_id, repair_case_id, proposed_by_side, round, created_at)
        VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
        """,
        id,
        room,
        repair,
        side.name());
    jdbc.update(
        """
        INSERT INTO record.proposal_line
          (id, room_record_id, proposal_id, line_kind, amount, created_at)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        room,
        id,
        side.name(),
        amount);
    return id;
  }

  private RequestTarget agendaRequest() {
    return RequestTarget.of(TargetKind.REPAIR_CASE, repair);
  }

  private Map<String, Object> caseRow() {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", repair);
  }

  private Map<String, Object> action(UUID proposal, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE proposal_id = ? AND type = ?", proposal, type);
  }

  private long countActions(UUID proposal, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE proposal_id = ? AND type = ?",
        Long.class,
        proposal,
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

  /** 세입자가 등록한 수리 건(첫 판 포함). 하자 등록 API를 거치지 않고 행만 넣는다. */
  private UUID insertRepair() {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    // 머리와 첫 판은 서로를 가리켜(지연 FK) 한 트랜잭션에 넣어야 커밋 때 검사를 지난다
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(tx -> {
          jdbc.update(
              """
              INSERT INTO state.repair_case (id, room_record_id, current_content_id, status,
                defect_ack, money_status, created_by_side, created_at, version)
              VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT',
                CURRENT_TIMESTAMP, 0)
              """,
              id,
              room,
              content);
          jdbc.update(
              """
              INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no,
                room_part_id, title, created_by_side, created_at)
              VALUES (?, ?, ?, 1, ?, '천장 누수', 'TENANT', CURRENT_TIMESTAMP)
              """,
              content,
              room,
              id,
              part);
        });
    return id;
  }
}
