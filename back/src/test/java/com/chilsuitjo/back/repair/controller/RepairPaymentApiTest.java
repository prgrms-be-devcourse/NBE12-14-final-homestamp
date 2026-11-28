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
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.FakePhotoService;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * 06 MONEY-1 지불 기록({@code POST /api/repair-cases/{id}/payments})을 보안·pipeline·진짜 저장(PostgreSQL)까지
 * 띄워 본다.
 *
 * <p>기대값: 06 4-0(만드는 행동은 201 + 만든 ID만, 같은 키면 처음과 같은 응답 + {@code Idempotent-Replayed}), 06 4-6 지불 기록
 * 줄, MONEY-1b(제3자는 관계로만), 03 F·06 LINK-1e(받을 상대가 있으면 금액 확인 요청), ROOM-2a(남의 것·보이지 않는 것은 404). 중복
 * 안내(MONEY-1a·1c)는 응답이 들어온 뒤 시나리오 테스트에서 본다.
 *
 * <p>응답 요청·사진은 가짜({@code FakeWaitService}·{@code FakePhotoService})라 상태만 확인한다. pipeline이 바깥 트랜잭션을
 * 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairPaymentApiTest {

  private static final String VALID =
      "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},"
          + "\"amount\":82000,\"paidOn\":\"2026-11-27\"}";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakeWaitService waits;
  @Autowired private FakePhotoService photos;
  @Autowired private RoomAccessService access;
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
    seat(room, PartySide.TENANT, tenant);
    seat(room, PartySide.LANDLORD, landlord);
    repair = insertRepair(room, PartySide.TENANT);
  }

  @Test
  void 지불을_남기면_201과_지불_ID를_받고_같은_키로_다시_보내면_같은_ID다() throws Exception {
    MvcResult first =
        record(repair, tenant, "m1", VALID)
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(RepairController.REPLAYED))
            .andReturn();
    UUID payment = idOf(first);

    record(repair, tenant, "m1", VALID)
        .andExpect(status().isCreated())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(payment.toString()));

    assertThat(paymentRow(payment))
        .containsEntry("room_record_id", room)
        .containsEntry("repair_case_id", repair)
        .containsEntry("response", "PENDING")
        .containsEntry("confirm_strength", "ONE_SIDE");
    assertThat(versionRow(payment))
        .containsEntry("no", 1)
        .containsEntry("payer_kind", "TENANT")
        .containsEntry("payee_kind", "VENDOR")
        .containsEntry("amount", 82_000L)
        .containsEntry("recorded_by_side", "TENANT");
    assertThat(countPayments()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE payment_id = ? AND type = 'RECORD_PAYMENT'",
                Long.class,
                payment))
        .isEqualTo(1);
  }

  @Test
  void 연결된_상대가_있으면_그_지불에_금액_확인_요청을_연다() throws Exception {
    UUID payment = idOf(record(repair, tenant, "m1", VALID).andReturn());

    assertThat(waits.statusOf(amountRequest(payment), RequestKind.AMOUNT_CONFIRM))
        .contains(RequestStatus.WAITING);
  }

  @Test
  void 받을_상대가_없으면_금액_확인_요청을_열지_않는다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'EMPTY', member_id = NULL, connected_at = NULL"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    UUID payment = idOf(record(repair, tenant, "m1", VALID).andReturn());

    assertThat(waits.statusOf(amountRequest(payment), RequestKind.AMOUNT_CONFIRM)).isEmpty();
  }

  @Test
  void 증빙_사진을_붙이면_그_지불에_붙고_증빙_있음으로_시작한다() throws Exception {
    String body =
        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"THIRD_PARTY\",\"label\":\"윗집\"},"
            + "\"amount\":82000,\"paidOn\":\"2026-11-27\",\"evidenceKind\":\"영수증\","
            + "\"uploadTickets\":[\""
            + UUID.randomUUID()
            + "\"]}";

    UUID payment =
        idOf(record(repair, tenant, "m1", body).andExpect(status().isCreated()).andReturn());

    assertThat(paymentRow(payment)).containsEntry("confirm_strength", "EVIDENCE");
    assertThat(versionRow(payment))
        .containsEntry("payee_label", "윗집")
        .containsEntry("evidence_kind", "영수증");
    List<PhotoCard> cards = cardsOf(new PhotoTarget.Ref(PhotoTargetType.PAYMENT, payment));
    assertThat(cards).hasSize(1);
    assertThat(cards.getFirst().stage()).isEqualTo(PhotoStage.EVIDENCE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{},\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},\"amount\":-1,"
            + "\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000}",
        "{\"payer\":{\"kind\":\"THIRD_PARTY\"},\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,"
            + "\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"THIRD_PARTY\",\"label\":\" \"},\"payee\":{\"kind\":\"VENDOR\"},"
            + "\"amount\":82000,\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"THIRD_PARTY\",\"label\":\"가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가가\"},"
            + "\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"TENANT\",\"label\":\"가족\"},\"payee\":{\"kind\":\"VENDOR\"},"
            + "\"amount\":82000,\"paidOn\":\"2026-11-27\"}",
        "{\"payer\":{\"kind\":\"TENANT\"},\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,"
            + "\"paidOn\":\"2026-11-27\",\"uploadTickets\":[\"\"]}"
      })
  void 칸이_빠지거나_틀리면_VALIDATION_FAILED이고_지불이_생기지_않는다(String body) throws Exception {
    record(repair, tenant, "m1", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(countPayments()).isZero();
  }

  @Test
  void 관계_설명에_주민등록번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    String body =
        "{\"payer\":{\"kind\":\"THIRD_PARTY\",\"label\":\"윗집 900101-1234567\"},"
            + "\"payee\":{\"kind\":\"VENDOR\"},\"amount\":82000,\"paidOn\":\"2026-11-27\"}";

    record(repair, tenant, "m1", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(countPayments()).isZero();
  }

  @Test
  void 남의_호실_수리_건이거나_없는_수리_건이면_404다() throws Exception {
    UUID other = insertRoom();
    seat(other, PartySide.TENANT, member());
    seat(other, PartySide.LANDLORD, member());
    UUID othersRepair = insertRepair(other, PartySide.TENANT);

    record(othersRepair, tenant, "m1", VALID)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    record(UUID.randomUUID(), tenant, "m2", VALID)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 공유를_그만둔_뒤_상대가_등록한_수리_건이면_404다() throws Exception {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = clock_timestamp() - interval '1 hour'"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID hidden = insertRepair(room, PartySide.LANDLORD);

    record(hidden, tenant, "m1", VALID)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  private ResultActions record(UUID repairCaseId, UUID memberId, String key, String body)
      throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/payments", repairCaseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static UUID idOf(MvcResult result) throws Exception {
    return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
  }

  private static RequestTarget amountRequest(UUID payment) {
    return RequestTarget.of(TargetKind.PAYMENT, payment);
  }

  /** 세입자가 보는 그 대상의 사진 카드(가짜 사진 창구). */
  private List<PhotoCard> cardsOf(PhotoTarget target) {
    RoomAccess viewer =
        new TransactionTemplate(transactionManager).execute(s -> access.require(room, tenant));
    return photos.cards(viewer, List.of(target)).get(target);
  }

  private Map<String, Object> paymentRow(UUID payment) {
    return jdbc.queryForMap("SELECT * FROM state.payment WHERE id = ?", payment);
  }

  private Map<String, Object> versionRow(UUID payment) {
    return jdbc.queryForMap(
        "SELECT v.* FROM record.payment_version v JOIN state.payment p"
            + " ON v.id = p.current_version_id WHERE p.id = ?",
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

  private void seat(UUID roomId, PartySide side, UUID memberId) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        roomId,
        side.name(),
        memberId);
  }

  /** 그 측이 지금 등록한 수리 건(첫 판 포함). 하자 등록 API를 거치지 않고 행만 넣는다. */
  private UUID insertRepair(UUID roomId, PartySide registeredBy) {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        roomId);
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    // 머리와 첫 판은 서로를 가리켜(지연 FK) 한 트랜잭션에 넣어야 커밋 때 검사를 지난다
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(tx -> {
          jdbc.update(
              """
              INSERT INTO state.repair_case (id, room_record_id, current_content_id, status,
                defect_ack, money_status, created_by_side, created_at, version)
              VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', ?, clock_timestamp(), 0)
              """,
              id,
              roomId,
              content,
              registeredBy.name());
          jdbc.update(
              """
              INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no,
                room_part_id, title, created_by_side, created_at)
              VALUES (?, ?, ?, 1, ?, '천장 누수', ?, clock_timestamp())
              """,
              content,
              roomId,
              id,
              part,
              registeredBy.name());
        });
    return id;
  }
}
