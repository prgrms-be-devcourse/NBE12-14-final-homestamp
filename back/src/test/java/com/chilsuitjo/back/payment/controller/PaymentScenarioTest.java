package com.chilsuitjo.back.payment.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.payment.rule.PaymentAmountConfirmed;
import com.chilsuitjo.back.payment.service.PaymentService;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 3-9 돈 시나리오 MONEY-1a·1c·2a를 실제 API(지불 기록 → 금액 응답 → 다시 기록)로 이어 본다. 규칙 하나하나는 {@code
 * PaymentTransitionsTest}, 한 단계짜리 거절은 {@code RepairPaymentApiTest}·{@code PaymentRespondApiTest}가
 * 본다.
 *
 * <p>기대값: 06 MONEY-1a(상대의 확인 전 기록은 새로 만들지 않고 그 기록으로 안내, 그래도 남기기로도 못 넘김), MONEY-1c(30일 안 비슷한 기록은 묻고,
 * 그래도 남기면 생김), MONEY-2a(이견은 원래 기록 옆, 기준 금액에 안 들어감), 05 2-5(기준 금액은 맞아요한 판의 합, 상대 당사자에게 준 돈은 뺌),
 * decisions 2026-11-16(다른 호실 ID로는 비어 있음).
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class PaymentScenarioTest {

  private static final String PAID_ON = "2026-11-27";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PaymentService payments;
  @Autowired private ApplicationEvents events;
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
  void MONEY_1a_상대의_확인_전_기록이_있으면_그_기록으로_안내하고_확인하면_비슷한_기록으로_바뀐다() throws Exception {
    UUID theirs = idOf(record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false));

    // 1a: 집주인이 같은 지불을 남기면 세입자 기록으로 안내하고, 그래도 남기기로도 못 넘긴다
    for (boolean similarConfirmed : new boolean[] {false, true}) {
      record(
              landlord,
              "l-" + similarConfirmed,
              "TENANT",
              "VENDOR",
              82_000,
              PAID_ON,
              similarConfirmed)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("DUPLICATE_PAYMENT"))
          .andExpect(jsonPath("$.duplicateOf").value(theirs.toString()))
          .andExpect(jsonPath("$.similar").value(false));
    }
    assertThat(countPayments()).isEqualTo(1);

    // 화면의 [확인]으로 그 기록에 맞아요를 누르면
    respond(landlord, theirs, "l-ack", "ACK", null).andExpect(status().isOk());

    // 이제 확인된 기록이라 1c(30일 안 비슷한 기록)로 묻고, 그래도 남기면 생긴다
    record(landlord, "l2", "TENANT", "VENDOR", 82_000, PAID_ON, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.duplicateOf").value(theirs.toString()))
        .andExpect(jsonPath("$.similar").value(true));
    record(landlord, "l3", "TENANT", "VENDOR", 82_000, PAID_ON, true)
        .andExpect(status().isCreated());
    assertThat(countPayments()).isEqualTo(2);
  }

  @Test
  void MONEY_1a_이견을_남긴_기록과_같은_지불도_새로_만들지_않는다() throws Exception {
    UUID theirs = idOf(record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false));
    respond(landlord, theirs, "l-dispute", "DISPUTE", "8만 원이었어요").andExpect(status().isOk());

    record(landlord, "l1", "TENANT", "VENDOR", 82_000, PAID_ON, true)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.duplicateOf").value(theirs.toString()))
        .andExpect(jsonPath("$.similar").value(false));
    assertThat(countPayments()).isEqualTo(1);
  }

  @Test
  void MONEY_1c_내_기록이_30일_안에_있으면_묻고_그래도_남기면_생기고_31일이_넘으면_묻지_않는다() throws Exception {
    UUID first = idOf(record(tenant, "t1", "TENANT", "VENDOR", 82_000, "2026-11-27", false));

    // 30일 뒤
    record(tenant, "t2", "TENANT", "VENDOR", 82_000, "2026-12-27", false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.duplicateOf").value(first.toString()))
        .andExpect(jsonPath("$.similar").value(true));
    assertThat(countPayments()).isEqualTo(1);

    // 화면은 "그래도 남길까요?" 뒤에 같은 키로 다시 보낸다. 거절된 요청은 행동이 남지 않아 새 요청이다
    record(tenant, "t2", "TENANT", "VENDOR", 82_000, "2026-12-27", true)
        .andExpect(status().isCreated());
    // 두 기록 모두와 31일 넘게 떨어짐
    record(tenant, "t3", "TENANT", "VENDOR", 82_000, "2027-01-28", false)
        .andExpect(status().isCreated());
    assertThat(countPayments()).isEqualTo(3);
  }

  @Test
  void 방향이나_금액이_다르면_비슷한_기록이_아니다() throws Exception {
    record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false)
        .andExpect(status().isCreated());

    record(tenant, "t2", "TENANT", "COUNTERPART", 82_000, PAID_ON, false)
        .andExpect(status().isCreated());
    record(tenant, "t3", "LANDLORD", "VENDOR", 82_000, PAID_ON, false)
        .andExpect(status().isCreated());
    record(tenant, "t4", "TENANT", "VENDOR", 80_000, PAID_ON, false)
        .andExpect(status().isCreated());
    assertThat(countPayments()).isEqualTo(4);
  }

  @Test
  void MONEY_2a_이견은_원래_기록_옆에_남고_나중에_맞아요하면_기준_금액에_들어간다() throws Exception {
    UUID payment = idOf(record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false));

    respond(landlord, payment, "l1", "DISPUTE", "영수증을 보여 주세요").andExpect(status().isOk());

    assertThat(currentAmount(payment)).isEqualTo(82_000L);
    assertThat(payments.hasMoney(room, repair)).isTrue();
    assertThat(payments.confirmedTotal(room, repair)).isZero();
    assertThat(events.stream(PaymentAmountConfirmed.class)).isEmpty();

    // 이견에서는 맞아요로만 바꿀 수 있다(AnswerSupport)
    respond(landlord, payment, "l2", "NEED_MORE", "아직 못 봤어요")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    respond(landlord, payment, "l3", "ACK", null).andExpect(status().isOk());

    assertThat(payments.confirmedTotal(room, repair)).isEqualTo(82_000);
    assertThat(events.stream(PaymentAmountConfirmed.class))
        .containsExactly(new PaymentAmountConfirmed(room, repair, payment));
  }

  @Test
  void 기준_금액은_맞아요한_지불만_더하고_상대_당사자에게_준_돈은_빼며_다른_호실로는_비어_있다() throws Exception {
    UUID vendor = idOf(record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false));
    UUID counterpart = idOf(record(tenant, "t2", "TENANT", "COUNTERPART", 30_000, PAID_ON, false));
    record(tenant, "t3", "TENANT", "THIRD_PARTY", 5_000, PAID_ON, false)
        .andExpect(status().isCreated());
    respond(landlord, vendor, "l1", "ACK", null).andExpect(status().isOk());
    respond(landlord, counterpart, "l2", "ACK", null).andExpect(status().isOk());

    assertThat(payments.confirmedTotal(room, repair)).isEqualTo(82_000);
    assertThat(events.stream(PaymentAmountConfirmed.class)).hasSize(2);

    UUID other = insertRoom();
    assertThat(payments.hasMoney(other, repair)).isFalse();
    assertThat(payments.confirmedTotal(other, repair)).isZero();
  }

  /**
   * 공유를 그만둔 동안 상대가 남기고 탈퇴했으면 그 지불은 나에게 보이지 않는다(03 6-4, 불변식 18). 탈퇴로 내 공유 중단 제한이 풀려 내가 다시 기록할 수
   * 있어도(06 CONS-3p) 그 지불은 중복 후보에 들지 않고, 답하려 하면 없는 기록과 같은 404다(ROOM-2a).
   */
  @Test
  void 공유를_그만둔_동안_탈퇴한_상대가_남긴_지불은_중복으로_세지_않고_답할_수도_없다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = clock_timestamp()"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID hidden = idOf(record(landlord, "l1", "TENANT", "VENDOR", 82_000, PAID_ON, false));
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', member_id = NULL"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    record(tenant, "t1", "TENANT", "VENDOR", 82_000, PAID_ON, false)
        .andExpect(status().isCreated());
    respond(tenant, hidden, "t2", "ACK", null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(countPayments()).isEqualTo(2);
  }

  /** 지불 기록 요청. 낸 사람이나 받는 사람이 제3자면 관계 설명 "윗집"을 넣는다. */
  private ResultActions record(
      UUID memberId,
      String key,
      String payer,
      String payee,
      long amount,
      String paidOn,
      boolean similarConfirmed)
      throws Exception {
    String body =
        "{\"payer\":"
            + party(payer)
            + ",\"payee\":"
            + party(payee)
            + ",\"amount\":"
            + amount
            + ",\"paidOn\":\""
            + paidOn
            + "\",\"similarConfirmed\":"
            + similarConfirmed
            + "}";
    return mvc.perform(
        post("/api/repair-cases/{id}/payments", repair)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String party(String kind) {
    return "THIRD_PARTY".equals(kind)
        ? "{\"kind\":\"THIRD_PARTY\",\"label\":\"윗집\"}"
        : "{\"kind\":\"" + kind + "\"}";
  }

  /** 지금 판(1)에 대한 금액 응답. */
  private ResultActions respond(
      UUID memberId, UUID payment, String key, String answer, String reason) throws Exception {
    String body =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":1}";
    return mvc.perform(
        post("/api/payments/{id}/respond", payment)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static UUID idOf(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private long currentAmount(UUID payment) {
    return jdbc.queryForObject(
        "SELECT v.amount FROM record.payment_version v JOIN state.payment p"
            + " ON v.id = p.current_version_id WHERE p.id = ?",
        Long.class,
        payment);
  }

  private long countPayments() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.payment WHERE room_record_id = ?", Long.class, room);
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
