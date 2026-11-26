package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.chilsuitjo.back.payment.service.FakePaymentService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 MONEY-3 부담 제안({@code POST /api/repair-cases/{id}/proposals})을 보안·pipeline·진짜 행동 기록
 * 저장(PostgreSQL)까지 띄워 본다. 제안을 SQL로 넣지 않고 API로 만든 뒤 동의까지 이어 본다.
 *
 * <p>기대값: 06 4-0(만드는 행동은 201 + 만든 ID만, 같은 키면 처음과 같은 응답 + {@code Idempotent-Replayed}), 06 4-6
 * {@code proposals} 줄, 03 E 표 "부담 제안"·"바꾸자는 새 제안", 03 1-3, decisions 2026-11-26(기다리는 안이 있으면 {@code
 * INVALID_STATE}).
 *
 * <p>응답 요청은 가짜({@code FakeWaitService})라 상태만 확인하고, 기준 금액은 가짜 지불 창구({@code FakePaymentService})에 적어
 * 둔다. pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairProposalApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private FakePaymentService payments;
  @Autowired private PlatformTransactionManager transactionManager;

  private final ExecutorService pool = Executors.newFixedThreadPool(3);

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

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void 첫_제안을_내면_201과_제안_ID를_받고_합의_대기가_된다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);

    MvcResult result =
        propose(landlord, "p1", split(60_000, 22_000))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(RepairController.REPLAYED))
            .andExpect(jsonPath("$.replayed").doesNotExist())
            .andExpect(jsonPath("$.agenda").doesNotExist())
            .andReturn();
    UUID proposal = idOf(result);

    assertThat(caseRow())
        .containsEntry("money_status", "PENDING")
        .containsEntry("current_proposal_id", proposal);
    assertThat(caseRow().get("agreed_proposal_id")).isNull();
    assertThat(proposalRow(proposal))
        .containsEntry("room_record_id", room)
        .containsEntry("repair_case_id", repair)
        .containsEntry("proposed_by_side", "LANDLORD")
        .containsEntry("round", 1)
        .containsEntry("no_cost", false)
        .containsEntry("exclude", false);
    assertThat(proposalRow(proposal).get("reason")).isNull();
    assertThat(lines(proposal))
        .containsExactlyInAnyOrder(
            Map.of("line_kind", "LANDLORD", "amount", 60_000L),
            Map.of("line_kind", "TENANT", "amount", 22_000L));
    assertThat(action(proposal, "PROPOSE"))
        .containsEntry("actor_member_id", landlord)
        .containsEntry("actor_side", "LANDLORD")
        .containsEntry("room_record_id", room);
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 실제_API로_첫_제안_뒤_상대가_동의하면_합의된다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    UUID proposal = idOf(propose(landlord, "p1", split(60_000, 22_000)).andReturn());

    agree(proposal, tenant, "a1").andExpect(status().isOk());

    assertThat(caseRow())
        .containsEntry("money_status", "AGREED")
        .containsEntry("agreed_proposal_id", proposal);
    assertThat(caseRow().get("current_proposal_id")).isNull();
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.ANSWERED);
  }

  @Test
  void 비용_없음_제안에_동의하면_비용_없음이_된다() throws Exception {
    UUID proposal =
        idOf(
            propose(tenant, "p1", "{\"lines\":[],\"noCost\":true}")
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(lines(proposal)).isEmpty();
    assertThat(proposalRow(proposal)).containsEntry("no_cost", true);

    agree(proposal, landlord, "a1").andExpect(status().isOk());

    assertThat(caseRow())
        .containsEntry("money_status", "NO_COST")
        .containsEntry("agreed_proposal_id", proposal);
  }

  @Test
  void 같은_키로_다시_보내면_제안과_응답_요청을_더_만들지_않고_처음_ID를_돌려준다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    UUID first =
        idOf(
            propose(landlord, "same", split(60_000, 22_000))
                .andExpect(status().isCreated())
                .andReturn());

    propose(landlord, "same", split(60_000, 22_000))
        .andExpect(status().isCreated())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));

    assertThat(countProposals()).isEqualTo(1);
    assertThat(lines(first)).hasSize(2);
    assertThat(countActions("PROPOSE")).isEqualTo(1);
    assertThat(caseRow()).containsEntry("current_proposal_id", first);
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void MONEY_3a_제안한_금액의_합이_전체_금액과_다르면_SUM_MISMATCH() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);

    propose(landlord, "p1", split(60_000, 20_000))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("SUM_MISMATCH"));

    assertThat(countProposals()).isZero();
    assertThat(countActions("PROPOSE")).isZero();
    assertThat(caseRow()).containsEntry("money_status", "UNDECIDED");
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL)).isEmpty();
  }

  @Test
  void MONEY_3d_합의_뒤_새_제안은_합의를_그대로_두고_바꾸자는_안이_된다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    UUID agreed = idOf(propose(landlord, "p1", split(60_000, 22_000)).andReturn());
    agree(agreed, tenant, "a1").andExpect(status().isOk());

    UUID change =
        idOf(
            propose(landlord, "p2", split(50_000, 32_000))
                .andExpect(status().isCreated())
                .andReturn());

    assertThat(caseRow())
        .containsEntry("money_status", "AGREED")
        .containsEntry("agreed_proposal_id", agreed)
        .containsEntry("current_proposal_id", change);
    assertThat(proposalRow(change)).containsEntry("round", 2);
    assertThat(waits.statusOf(agendaRequest(), RequestKind.PROPOSAL))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 이견_뒤_새_제안은_합의_대기가_되고_회차가_는다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    UUID first = idOf(propose(landlord, "p1", split(60_000, 22_000)).andReturn());
    dispute(first, tenant, "d1", "영수증이 달라요").andExpect(status().isOk());

    UUID next =
        idOf(propose(tenant, "p2", split(82_000, 0)).andExpect(status().isCreated()).andReturn());

    assertThat(caseRow())
        .containsEntry("money_status", "PENDING")
        .containsEntry("current_proposal_id", next);
    assertThat(proposalRow(next))
        .containsEntry("round", 2)
        .containsEntry("proposed_by_side", "TENANT");
  }

  @Test
  void 기다리는_안이_있으면_내_안이든_상대_안이든_INVALID_STATE() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    UUID waiting = idOf(propose(landlord, "p1", split(60_000, 22_000)).andReturn());

    propose(tenant, "p2", split(82_000, 0))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    propose(landlord, "p3", split(50_000, 32_000))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));

    assertThat(countProposals()).isEqualTo(1);
    assertThat(caseRow()).containsEntry("current_proposal_id", waiting);
  }

  @Test
  void 두_측이_동시에_첫_제안을_보내면_먼저_저장된_것만_남고_뒤_것은_INVALID_STATE() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);
    CountDownLatch release = holdRoomLock();

    Future<MvcResult> byLandlord =
        pool.submit(() -> propose(landlord, "c1", split(60_000, 22_000)).andReturn());
    Future<MvcResult> byTenant =
        pool.submit(() -> propose(tenant, "c2", split(82_000, 0)).andReturn());
    awaitLockWaiters(2); // 두 요청이 모두 호실 잠금에서 기다린다
    release.countDown();

    List<Integer> statuses =
        List.of(
            byLandlord.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
            byTenant.get(10, TimeUnit.SECONDS).getResponse().getStatus());
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(countProposals()).isEqualTo(1);
    assertThat(countActions("PROPOSE")).isEqualTo(1);
    assertThat(caseRow()).containsEntry("money_status", "PENDING");
  }

  @Test
  void 줄_모양이_수리비_부담에_맞지_않으면_VALIDATION_FAILED() throws Exception {
    List<String> bodies =
        List.of(
            "{\"lines\":[],\"noCost\":false}", // 줄 없음
            "{\"noCost\":false}", // 줄 칸 없음
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":82000}],\"noCost\":true}", // 비용 없음 + 줄
            "{\"lines\":[{\"kind\":\"DEDUCT\",\"amount\":82000}]}", // 정산 항목 종류
            "{\"lines\":[{\"amount\":82000}]}", // 종류 없음
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":-1}]}", // 음수
            "{\"lines\":[{\"kind\":\"THIRD_PARTY\",\"amount\":82000}]}", // 제3자 설명 없음
            "{\"lines\":[{\"kind\":\"THIRD_PARTY\",\"thirdPartyLabel\":\" \",\"amount\":82000}]}",
            "{\"lines\":[{\"kind\":\"THIRD_PARTY\",\"thirdPartyLabel\":\""
                + "가".repeat(31)
                + "\",\"amount\":82000}]}", // 제3자 설명 30자 초과
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"thirdPartyLabel\":\"윗집\",\"amount\":82000}]}",
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":82000}],\"reason\":\"그냥\"}", // 수리비 사유
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":9223372036854775807},"
                + "{\"kind\":\"TENANT\",\"amount\":1}]}", // 합이 넘침
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":0}]}", // 합계 0원: 비용 없음으로
            "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":0},"
                + "{\"kind\":\"TENANT\",\"amount\":0}]}"); // 줄이 여럿이어도 합계 0원
    int n = 0;
    for (String body : bodies) {
      propose(landlord, "v" + n++, body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    assertThat(countProposals()).isZero();
    assertThat(countActions("PROPOSE")).isZero();
  }

  @Test
  void 확인된_지불이_없으면_합계_0원_안은_막고_비용_없음으로만_제안한다() throws Exception {
    // 기준 금액 0: 0원 줄 안이 합계 검사를 지나 AGREED(0원 합의)가 되면 NO_COST와 같은 뜻이 둘로 남는다
    propose(landlord, "p1", "{\"lines\":[{\"kind\":\"LANDLORD\",\"amount\":0}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    UUID proposal =
        idOf(
            propose(landlord, "p2", "{\"noCost\":true}")
                .andExpect(status().isCreated())
                .andReturn());
    agree(proposal, tenant, "a1").andExpect(status().isOk());

    assertThat(caseRow()).containsEntry("money_status", "NO_COST");
    assertThat(countProposals()).isEqualTo(1);
  }

  @Test
  void 한쪽이_0원인_나눈_안은_받는다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);

    // 프론트 [나눠서]는 세입자 0원 줄도 함께 보낸다(repair-discussions.tsx lines)
    UUID proposal =
        idOf(
            propose(
                    landlord,
                    "p1",
                    "{\"lines\":[{\"kind\":\"TENANT\",\"amount\":0},"
                        + "{\"kind\":\"LANDLORD\",\"amount\":82000}]}")
                .andExpect(status().isCreated())
                .andReturn());

    assertThat(lines(proposal))
        .containsExactlyInAnyOrder(
            Map.of("line_kind", "TENANT", "amount", 0L),
            Map.of("line_kind", "LANDLORD", "amount", 82_000L));
  }

  @Test
  void 빈_사유는_없는_것으로_보고_제3자_줄을_받는다() throws Exception {
    payments.seedConfirmedTotal(room, repair, 82_000);

    UUID proposal =
        idOf(
            propose(
                    tenant,
                    "p1",
                    "{\"lines\":[{\"kind\":\"THIRD_PARTY\",\"thirdPartyLabel\":\"윗집\","
                        + "\"amount\":50000},{\"kind\":\"LANDLORD\",\"amount\":32000}],"
                        + "\"reason\":\"\"}")
                .andExpect(status().isCreated())
                .andReturn());

    assertThat(proposalRow(proposal).get("reason")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT third_party_label FROM record.proposal_line"
                    + " WHERE proposal_id = ? AND line_kind = 'THIRD_PARTY'",
                String.class,
                proposal))
        .isEqualTo("윗집");
  }

  @Test
  void 제3자_설명에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    propose(
            landlord,
            "p1",
            "{\"lines\":[{\"kind\":\"THIRD_PARTY\",\"thirdPartyLabel\":\"900101-1234567\","
                + "\"amount\":82000}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(countProposals()).isZero();
  }

  @Test
  void 없는_수리_건이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    mvc.perform(
            post("/api/repair-cases/{id}/proposals", UUID.randomUUID())
                .with(loginAs(landlord))
                .header("Idempotency-Key", "p1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(split(82_000, 0)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    propose(member(), "p2", split(82_000, 0))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    assertThat(countProposals()).isZero();
  }

  @Test
  void Idempotency_Key가_없으면_400() throws Exception {
    mvc.perform(
            post("/api/repair-cases/{id}/proposals", repair)
                .with(loginAs(landlord))
                .contentType(MediaType.APPLICATION_JSON)
                .content(split(82_000, 0)))
        .andExpect(status().isBadRequest());

    assertThat(countProposals()).isZero();
  }

  private ResultActions propose(UUID memberId, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/proposals", repair)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
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
            .content("{\"reason\":\"" + reason + "\"}"));
  }

  /** 집주인·세입자 줄로 나눈 안. 0원 줄은 넣지 않는다. */
  private static String split(long landlordAmount, long tenantAmount) {
    StringBuilder lines = new StringBuilder();
    if (landlordAmount > 0) {
      lines.append("{\"kind\":\"LANDLORD\",\"amount\":").append(landlordAmount).append('}');
    }
    if (tenantAmount > 0) {
      if (!lines.isEmpty()) {
        lines.append(',');
      }
      lines.append("{\"kind\":\"TENANT\",\"amount\":").append(tenantAmount).append('}');
    }
    return "{\"lines\":[" + lines + "],\"noCost\":false}";
  }

  private static UUID idOf(MvcResult result) throws Exception {
    return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
  }

  /** 다른 트랜잭션에서 호실 행을 잠가 둔다. 돌려준 래치를 내리면 커밋해 잠금을 푼다. */
  private CountDownLatch holdRoomLock() {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.submit(() ->
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> {
              jdbc.queryForList("SELECT id FROM state.room_record WHERE id = ? FOR UPDATE", room);
              locked.countDown();
              await(release);
            }));
    await(locked);
    return release;
  }

  private void awaitLockWaiters(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class)
        < count) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("두 요청이 잠금 대기에 들어가지 않았다");
      }
      Thread.sleep(10);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("기다리다 시간이 지났다");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private RequestTarget agendaRequest() {
    return RequestTarget.of(TargetKind.REPAIR_CASE, repair);
  }

  private Map<String, Object> caseRow() {
    return jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", repair);
  }

  private Map<String, Object> proposalRow(UUID proposal) {
    return jdbc.queryForMap("SELECT * FROM record.proposal WHERE id = ?", proposal);
  }

  private List<Map<String, Object>> lines(UUID proposal) {
    return jdbc.queryForList(
        "SELECT line_kind, amount FROM record.proposal_line WHERE proposal_id = ?", proposal);
  }

  private long countProposals() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.proposal WHERE repair_case_id = ?", Long.class, repair);
  }

  private Map<String, Object> action(UUID proposal, String type) {
    return jdbc.queryForMap(
        "SELECT * FROM record.action WHERE proposal_id = ? AND type = ?", proposal, type);
  }

  private long countActions(String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE room_record_id = ? AND type = ?",
        Long.class,
        room,
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
