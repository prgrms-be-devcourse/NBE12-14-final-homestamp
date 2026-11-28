package com.chilsuitjo.back.payment.controller;

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
import com.chilsuitjo.back.payment.rule.PaymentAmountConfirmed;
import com.chilsuitjo.back.payment.service.PaymentService;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 MONEY-2 금액 응답({@code POST /api/payments/{id}/respond})을 보안·pipeline·진짜 저장(PostgreSQL)까지 띄워 본다.
 * 지불은 SQL로 넣지 않고 지불 기록 API로 만든다.
 *
 * <p>기대값: 06 4-0(응답은 200 + {@code AnswerResult}, 같은 키면 지금 상태 + {@code Idempotent-Replayed}), 03
 * F(맞아요면 같이 확인한 판이 정해지고 기준 금액에 들어감), MONEY-2a(이견은 원래 기록 옆에 남고 기준 금액에 들어가지 않음), {@code
 * AnswerSupport} 순서, {@code PaymentAmountConfirmed}(맞아요일 때만, 저장한 뒤 같은 트랜잭션).
 *
 * <p>응답 요청은 가짜({@code FakeWaitService})라 상태만 확인한다. pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로
 * 되돌리지 않는다. 테스트마다 새 호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class PaymentRespondApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private PaymentService payments;
  @Autowired private ApplicationEvents events;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID repair;
  private UUID payment;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
    repair = insertRepair();
    String created =
        mvc.perform(
                post("/api/repair-cases/{id}/payments", repair)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "m1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},"
                            + "\"amount\":82000,\"paidOn\":\"2026-11-27\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    payment = UUID.fromString(JsonPath.read(created, "$.id"));
  }

  @Test
  void 상대가_맞아요하면_같이_확인한_판이_정해지고_기준_금액에_들어간다() throws Exception {
    respond(landlord, "r1", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(PaymentController.REPLAYED))
        .andExpect(jsonPath("$.targetId").value(payment.toString()))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.state").value("ACK"));

    Map<String, Object> row = paymentRow();
    assertThat(row).containsEntry("response", "ACK").containsEntry("confirm_strength", "BOTH");
    assertThat(row.get("confirmed_version_id")).isEqualTo(row.get("current_version_id"));
    assertThat(waits.statusOf(amountRequest(), RequestKind.AMOUNT_CONFIRM))
        .contains(RequestStatus.ANSWERED);
    assertThat(events.stream(PaymentAmountConfirmed.class))
        .containsExactly(new PaymentAmountConfirmed(room, repair, payment));
    assertThat(payments.confirmedTotal(room, repair)).isEqualTo(82_000);
  }

  @Test
  void 이견은_원래_기록_옆에_남고_기준_금액에_들어가지_않는다() throws Exception {
    respond(landlord, "r1", "{\"answer\":\"DISPUTE\",\"reason\":\"8만 원이었어요\",\"seenVersionNo\":1}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISPUTE"));

    Map<String, Object> row = paymentRow();
    assertThat(row)
        .containsEntry("response", "DISPUTE")
        .containsEntry("confirm_strength", "ONE_SIDE");
    assertThat(row.get("confirmed_version_id")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.payment_version WHERE payment_id = ?",
                Long.class,
                payment))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM record.action WHERE payment_id = ? AND type = 'RESPOND_AMOUNT'",
                String.class,
                payment))
        .isEqualTo("8만 원이었어요");
    assertThat(waits.statusOf(amountRequest(), RequestKind.AMOUNT_CONFIRM))
        .contains(RequestStatus.ANSWERED);
    assertThat(events.stream(PaymentAmountConfirmed.class)).isEmpty();
    assertThat(payments.confirmedTotal(room, repair)).isZero();
  }

  @Test
  void 같은_키로_다시_보내면_지금_응답을_같은_모양으로_돌려준다() throws Exception {
    String body = "{\"answer\":\"ACK\",\"seenVersionNo\":1}";
    respond(landlord, "r1", body).andExpect(status().isOk());

    respond(landlord, "r1", body)
        .andExpect(status().isOk())
        .andExpect(header().string(PaymentController.REPLAYED, "true"))
        .andExpect(jsonPath("$.targetId").value(payment.toString()))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.state").value("ACK"));

    assertThat(events.stream(PaymentAmountConfirmed.class)).hasSize(1);
  }

  @Test
  void 기록한_측은_답할_수_없다() throws Exception {
    respond(tenant, "r1", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_COUNTERPART_SIDE"));
  }

  @Test
  void 본_판이_지금_판이_아니면_VERSION_CONFLICT() throws Exception {
    respond(landlord, "r1", "{\"answer\":\"ACK\",\"seenVersionNo\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void 맞아요_뒤에는_다시_답할_수_없다() throws Exception {
    respond(landlord, "r1", "{\"answer\":\"ACK\",\"seenVersionNo\":1}").andExpect(status().isOk());

    respond(landlord, "r2", "{\"answer\":\"DISPUTE\",\"reason\":\"다시 보니 달라요\",\"seenVersionNo\":1}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACK_FINAL"));
  }

  @Test
  void 답이_없거나_사유에_주민등록번호_형태가_있으면_거절한다() throws Exception {
    respond(landlord, "r1", "{\"seenVersionNo\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    respond(
            landlord,
            "r2",
            "{\"answer\":\"DISPUTE\",\"reason\":\"900101-1234567\",\"seenVersionNo\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(paymentRow()).containsEntry("response", "PENDING");
  }

  @Test
  void 없는_지불이거나_당사자가_아니면_404다() throws Exception {
    mvc.perform(
            post("/api/payments/{id}/respond", UUID.randomUUID())
                .with(loginAs(landlord))
                .header("Idempotency-Key", "r1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"ACK\",\"seenVersionNo\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    respond(member(), "r2", "{\"answer\":\"ACK\",\"seenVersionNo\":1}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  private ResultActions respond(UUID memberId, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/payments/{id}/respond", payment)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private RequestTarget amountRequest() {
    return RequestTarget.of(TargetKind.PAYMENT, payment);
  }

  private Map<String, Object> paymentRow() {
    return jdbc.queryForMap("SELECT * FROM state.payment WHERE id = ?", payment);
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
