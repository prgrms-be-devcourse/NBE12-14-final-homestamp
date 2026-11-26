package com.chilsuitjo.back.repair.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.photo.dto.PhotoCard;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoStage;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import com.chilsuitjo.back.photo.service.FakePhotoService;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 06 REP-1 하자 등록을 보안·pipeline·진짜 행동 기록 저장(PostgreSQL)까지 띄워 본다. 사진은 가짜 사진 창구({@code
 * FakePhotoService})가 받고(#167), 알리기는 아직 없다(#134).
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다. 잠금(
 * {@code JpaRoomGateReader})·부위({@code JpaRoomQueryService})·자리 상태({@code JpaCounterpartReader})가
 * 모두 진짜라 {@code room_record}·{@code party}·{@code room_part} 행으로 정한다(#143, #140).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairRegisterApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private FakePhotoService photos;
  @Autowired private RoomAccessService access;
  @Autowired private PlatformTransactionManager transactionManager;

  private static final Instant STOPPED = Instant.parse("2026-11-01T00:00:00Z");

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room();
    tenant = member();
    landlord = member();
    part = part(room, false);
    // 잠금·당사자 확인은 진짜(JpaRoomGateReader)라 party 행으로 정한다: 세입자(나)와 집주인 모두 연결됨
    seat(PartySide.TENANT, tenant, "CONNECTED");
    seat(PartySide.LANDLORD, landlord, "CONNECTED");
  }

  // ---- 공유를 그만둔 뒤 상대가 바꾼 부위 (#183, 03 6-6 9번·불변식 18) ----
  // 세입자가 공유를 그만둔 뒤 집주인이 부위를 바꿔도, 세입자의 하자 등록은 세입자에게 보이는 부위 목록(#180)과 같게 판단한다

  @Test
  void 그만둔_뒤_상대가_숨긴_부위는_내게는_보이는_부위라_등록된다() throws Exception {
    stopSharing(PartySide.TENANT);
    partAction(landlord, part, "hide").andExpect(status().isOk());

    register("hidden-by-other", body(part, "누수", null)).andExpect(status().isCreated());
  }

  @Test
  void 그만둔_뒤_상대가_다시_보이게_한_부위는_내게는_숨긴_부위라_VALIDATION_FAILED() throws Exception {
    partAction(tenant, part, "hide").andExpect(status().isOk());
    stopSharing(PartySide.TENANT);
    partAction(landlord, part, "show").andExpect(status().isOk());

    register("shown-by-other", body(part, "누수", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(count("state.repair_case")).isZero();
  }

  // ---- 꺼 둔 설비·가전 (#182, 06 ROOM-3d): "이 집에 없음"이라 새 하자를 받지 않는다 ----

  @Test
  void 꺼_둔_설비_가전에는_VALIDATION_FAILED이고_켜면_등록된다() throws Exception {
    UUID fridge = appliance(room);
    toggle(tenant, fridge, false).andExpect(status().isOk());

    register("off-appliance", body(fridge, "고장", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(count("state.repair_case")).isZero();

    toggle(tenant, fridge, true).andExpect(status().isOk());
    register("on-appliance", body(fridge, "고장", null)).andExpect(status().isCreated());
  }

  @Test
  void 하자가_있는_설비_가전을_꺼도_막지_않고_수리_건은_그대로다() throws Exception {
    UUID fridge = appliance(room);
    register("before-off", body(fridge, "고장", null)).andExpect(status().isCreated());

    toggle(tenant, fridge, false).andExpect(status().isOk());

    assertThat(count("state.repair_case")).isOne();
  }

  @Test
  void 그만둔_뒤_상대가_끈_설비_가전은_내게는_켜진_설비라_등록된다() throws Exception {
    UUID fridge = appliance(room);
    stopSharing(PartySide.TENANT);
    toggle(landlord, fridge, false).andExpect(status().isOk());

    register("off-by-other", body(fridge, "고장", null)).andExpect(status().isCreated());
  }

  @Test
  void 그만둔_뒤_상대가_더한_부위는_내게_없는_부위라_NOT_FOUND() throws Exception {
    stopSharing(PartySide.TENANT);
    UUID added = addPart(landlord, "다락");

    register("added-by-other", body(added, "누수", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(count("state.repair_case")).isZero();
  }

  @Test
  void 상대가_그만뒀어도_그_뒤_내가_더한_부위에는_등록된다() throws Exception {
    // 보는 사람 자신의 행동은 고정 시각 뒤에도 보인다(PartVisibility). 남은 사람은 부위를 바꿀 수 있다
    stopSharing(PartySide.LANDLORD);
    UUID added = addPart(tenant, "다락");

    register("added-by-me", body(added, "누수", null)).andExpect(status().isCreated());
  }

  @Test
  void 등록하면_수리_건과_판_1과_행동이_함께_생긴다() throws Exception {
    String id =
        register("k1", body(part, "천장 누수", "비 오면 물이 떨어져요"))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(RepairController.REPLAYED))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    UUID caseId = UUID.fromString(id);

    Map<String, Object> head =
        jdbc.queryForMap("SELECT * FROM state.repair_case WHERE id = ?", caseId);
    assertThat(head)
        .containsEntry("room_record_id", room)
        .containsEntry("status", "NOT_STARTED")
        .containsEntry("defect_ack", "PENDING")
        .containsEntry("money_status", "UNDECIDED")
        .containsEntry("created_by_side", "TENANT");

    Map<String, Object> content =
        jdbc.queryForMap("SELECT * FROM record.repair_content WHERE repair_case_id = ?", caseId);
    assertThat(content)
        .containsEntry("id", head.get("current_content_id"))
        .containsEntry("no", 1)
        .containsEntry("room_part_id", part)
        .containsEntry("title", "천장 누수")
        .containsEntry("description", "비 오면 물이 떨어져요");

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE repair_case_id = ?", caseId);
    assertThat(action)
        .containsEntry("type", "REGISTER")
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT")
        .containsEntry("room_seq", 1L);
    // 제목·설명은 판에만 두고 payload에는 사진 지문만 남는다(05 2-3)
    assertThat(action.get("payload").toString()).startsWith("{\"photos\": [");
    // 새 판을 만드는 행동이라 그 판 #1을 지문으로 남긴다(안내서 3-3). 칸 순서도 형식의 일부다.
    DigestInput expected =
        new DigestInput(
            "repair_content",
            (UUID) content.get("id"),
            1,
            List.of(
                new DigestField("room_part_id", part.toString()),
                new DigestField("title", "천장 누수"),
                new DigestField("description", "비 오면 물이 떨어져요")));
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(expected));
    // 상대가 연결돼 있어 바로 보인다. 시각은 행동 시각과 같다(decisions 2026-11-18, #94 at()).
    assertThat(head.get("first_shown_at")).isEqualTo(action.get("created_at"));
    assertThat(head.get("created_at")).isEqualTo(action.get("created_at"));
  }

  @Test
  void 같은_키로_다시_보내면_새로_만들지_않고_처음_수리_건을_돌려준다() throws Exception {
    String first =
        register("same", body(part, "곰팡이", null))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    register("same", body(part, "곰팡이", null))
        .andExpect(status().isCreated())
        .andExpect(header().string(RepairController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")));

    assertThat(count("state.repair_case")).isEqualTo(1);
    assertThat(count("record.repair_content")).isEqualTo(1);
  }

  @Test
  void 설명은_없어도_등록된다() throws Exception {
    register("no-desc", body(part, "문고리 고장", null)).andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM record.repair_content WHERE room_record_id = ?",
                String.class,
                room))
        .isNull();
  }

  @Test
  void 제목은_앞뒤_공백을_자르고_공백뿐인_설명은_없는_것으로_남긴다() throws Exception {
    register("trim", body(part, "  천장 누수  ", " \\n\\t ")).andExpect(status().isCreated());

    Map<String, Object> content =
        jdbc.queryForMap(
            "SELECT title, description FROM record.repair_content WHERE room_record_id = ?", room);
    assertThat(content.get("title")).isEqualTo("천장 누수");
    assertThat(content.get("description")).isNull();
  }

  @Test
  void 설명은_2000자까지_받는다() throws Exception {
    register("desc-2000", body(part, "누수", "가".repeat(2000))).andExpect(status().isCreated());
    register("desc-2001", body(part, "누수", "가".repeat(2001)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(count("state.repair_case")).isEqualTo(1);
  }

  @Test
  void 제목이나_설명에_주민번호_형태가_있으면_RESIDENT_ID_BLOCKED() throws Exception {
    register("rrn-title", body(part, "900101-1234567 누수", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    register("rrn-desc", body(part, "누수", "집주인 9001011234567"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  // ---- 사진(#167) ----

  @Test
  void REP_1a_사진은_수리_건에_수리_전_단계로_붙고_지문이_행동_payload에_남는다() throws Exception {
    UUID caseId = created(register("photos", body(part, "천장 누수", null, tickets(2))));

    List<PhotoCard> cards = cardsOf(caseId);
    assertThat(cards).hasSize(2);
    assertThat(cards)
        .allSatisfy(card -> {
          assertThat(card.stage()).isEqualTo(PhotoStage.BEFORE);
          assertThat(card.uploadedBy()).isEqualTo(PartySide.TENANT);
        });
    assertThat(cards).extracting(PhotoCard::sortOrder).containsExactly(0, 1);

    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM record.action WHERE repair_case_id = ?",
            String.class,
            caseId);
    for (PhotoCard card : cards) {
      assertThat(payload).contains(card.id().toString());
    }
    // 사진 시각은 행동 시각과 같다(불변식 10)
    Timestamp actionAt =
        jdbc.queryForObject(
            "SELECT created_at FROM record.action WHERE repair_case_id = ?",
            Timestamp.class,
            caseId);
    assertThat(cards)
        .allSatisfy(card -> assertThat(card.createdAt()).isEqualTo(actionAt.toInstant()));
  }

  @Test
  void REP_1d_사진이_없으면_PHOTO_REQUIRED() throws Exception {
    register("no-photo", body(part, "누수", null, null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));
    register("empty-photo", body(part, "누수", null, "[]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 사진은_한_번에_10장까지이고_빈_표는_받지_않는다() throws Exception {
    register("eleven", body(part, "누수", null, tickets(11)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    register("blank", body(part, "누수", null, "[\" \"]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    register("null", body(part, "누수", null, "[null]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(count("state.repair_case")).isZero();

    register("ten", body(part, "누수", null, tickets(10))).andExpect(status().isCreated());
  }

  @Test
  void 사진_검사에_실패하면_사진_번호와_함께_거절되고_아무것도_남지_않는다() throws Exception {
    String bad = UUID.randomUUID().toString();
    photos.failTicket(bad, ErrorCode.PHOTO_CHECK_FAILED);

    register("bad-photo", body(part, "누수", null, "[\"" + UUID.randomUUID() + "\",\"" + bad + "\"]"))
        .andExpect(jsonPath("$.code").value("PHOTO_CHECK_FAILED"))
        .andExpect(jsonPath("$.photoIndex").value(1));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.repair_content")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 같은_키로_다시_보내면_사진을_다시_붙이지_않는다() throws Exception {
    String json = body(part, "누수", null, tickets(2));
    UUID caseId = created(register("same-photo", json));

    register("same-photo", json).andExpect(header().string(RepairController.REPLAYED, "true"));

    assertThat(cardsOf(caseId)).hasSize(2);
  }

  @Test
  void 상대가_아직_연결되지_않았으면_처음_보인_시각은_비어_있다() throws Exception {
    changeParty(PartySide.LANDLORD, null, "INVITED", null);

    register("invited", body(part, "창문 틈", null)).andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "SELECT first_shown_at FROM state.repair_case WHERE room_record_id = ?",
                Timestamp.class,
                room))
        .isNull();
  }

  @Test
  void 내가_공유를_그만뒀어도_등록은_되지만_상대에게_보이지_않는다() throws Exception {
    // 공유를 그만둔 사람도 나만 보는 기록으로 남길 수 있다(ActionType WHILE_SHARING_STOPPED, 03 B)
    changeParty(PartySide.TENANT, tenant, "SHARING_STOPPED", STOPPED);

    register("my-stop", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 상대가_공유를_그만뒀으면_처음_보인_시각은_비어_있다() throws Exception {
    changeParty(PartySide.LANDLORD, member(), "SHARING_STOPPED", STOPPED);

    register("their-stop", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 상대가_탈퇴했으면_처음_보인_시각은_비어_있다() throws Exception {
    // 탈퇴하면 공유 중단 시각이 남아 있어도 보지 않는다(06 CONS-3q). 연결이 아니라 보이지 않는다
    changeParty(PartySide.LANDLORD, member(), "WITHDRAWN", STOPPED);

    register("withdrawn", body(part, "누수", null)).andExpect(status().isCreated());

    assertThat(firstShownAt()).isNull();
  }

  @Test
  void 정산이_끝난_호실에는_SETTLED() throws Exception {
    // 정산 완료면 거주 종료 시각도 있다(V1 CHECK). 정산 완료가 거주 종료보다 먼저 거절된다(09 3-1 순서 3)
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    register("settled", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 합치기_전_기록에는_JOINED_RECORD() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        room(),
        room);

    register("joined", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOINED_RECORD"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 거주가_끝난_호실에는_새_수리_건이_없다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    register("ended", body(part, "누수", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_ENDED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 부위나_제목이_없으면_VALIDATION_FAILED() throws Exception {
    for (String json :
        new String[] {
          "{\"title\":\"누수\"}",
          "{\"roomPartId\":\"" + part + "\"}",
          "{\"roomPartId\":\"" + part + "\",\"title\":\"   \"}",
          "{\"roomPartId\":\"" + part + "\",\"title\":\"" + "가".repeat(101) + "\"}"
        }) {
      register("v-" + json.hashCode(), json)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(count("state.repair_case")).isZero();
  }

  @Test
  void 남의_부위면_NOT_FOUND이고_숨긴_부위면_VALIDATION_FAILED() throws Exception {
    UUID otherRoomPart = UUID.randomUUID();
    register("other-part", body(otherRoomPart, "누수", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    UUID hidden = part(room, true);
    register("hidden-part", body(hidden, "누수", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(count("state.repair_case")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void 그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    UUID stranger = UUID.randomUUID();

    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .with(loginAs(stranger))
                .header("Idempotency-Key", "stranger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 멱등_키가_없으면_400() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .with(loginAs(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void 로그인하지_않으면_401() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/repair-cases", room)
                .header("Idempotency-Key", "anon")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(part, "누수", null)))
        .andExpect(status().isUnauthorized());
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 세입자가 보는 이 수리 건의 사진 카드(가짜 사진 창구). */
  private List<PhotoCard> cardsOf(UUID caseId) {
    RoomAccess viewer =
        new TransactionTemplate(transactionManager).execute(s -> access.require(room, tenant));
    PhotoTarget target = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, caseId);
    return photos.cards(viewer, List.of(target)).get(target);
  }

  private ResultActions register(String key, String json) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /** 사진 한 장을 붙인 등록 본문. 사진은 필수라(REP-1d) 다른 칸을 시험할 때도 한 장을 넣는다. */
  private static String body(UUID partId, String title, String description) {
    return body(partId, title, description, "[\"" + UUID.randomUUID() + "\"]");
  }

  private static String body(UUID partId, String title, String description, String ticketsJson) {
    return "{\"roomPartId\":\""
        + partId
        + "\",\"title\":\""
        + title
        + "\""
        + (description == null ? "" : ",\"description\":\"" + description + "\"")
        + (ticketsJson == null ? "" : ",\"uploadTickets\":" + ticketsJson)
        + "}";
  }

  private static String tickets(int count) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    return json.append(']').toString();
  }

  private UUID room() {
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

  /** 이 호실의 당사자 자리. 진짜 잠금(JpaRoomGateReader)이 이 행으로 당사자·측·공유 중단을 본다. */
  private void seat(PartySide side, UUID memberId, String status) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        memberId,
        status);
  }

  /** 그 측 자리의 회원·상태·공유 중단 시각을 바꾼다(JpaCounterpartReader가 이 값을 읽는다). */
  private void changeParty(PartySide side, UUID memberId, String status, Instant sharingStoppedAt) {
    jdbc.update(
        "UPDATE state.party SET member_id = ?, status = ?, sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        memberId,
        status,
        sharingStoppedAt == null ? null : Timestamp.from(sharingStoppedAt),
        room,
        side.name());
  }

  /**
   * 호실을 만들 때 기본 목록(V7)에서 복사된 부위. 공유를 그만둔 뒤에는 부위 행동으로 보이는 상태를 다시 계산하는데(#180), 복사된 부위는 행동 없이
   * "있음·보임"에서 시작하므로 템플릿을 가리켜야 실제 호실과 같다.
   */
  private UUID part(UUID roomId, boolean hidden) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, hidden_at, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, ?, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone <> '설비·가전' ORDER BY t.sort_order LIMIT 1
        """,
        id,
        roomId,
        hidden ? Timestamp.from(STOPPED) : null);
    return id;
  }

  /**
   * 이 측이 지금 공유를 그만둔 것으로 만든다. 시각은 이 호실의 마지막 행동 시각(없으면 {@link #STOPPED})이라 그때까지의 행동은 양쪽에 보이고, 그 뒤 남은
   * 행동은 상대에게 보이지 않는다.
   */
  private void stopSharing(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = COALESCE("
            + "(SELECT max(created_at) FROM record.action WHERE room_record_id = ?), ?)"
            + " WHERE room_record_id = ? AND side = ?",
        room,
        Timestamp.from(STOPPED),
        room,
        side.name());
  }

  /** 부위 숨기기·다시 보이기(진짜 부위 API, 행동이 남는다). */
  private ResultActions partAction(UUID memberId, UUID partId, String verb) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/parts/{partId}/" + verb, room, partId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", verb + "-" + UUID.randomUUID()));
  }

  /** 기본 목록(V7)에서 복사한 켜진 설비·가전 부위(켜고 끔 대상). */
  private UUID appliance(UUID roomId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '설비·가전' ORDER BY t.sort_order LIMIT 1
        """,
        id,
        roomId);
    return id;
  }

  /** 설비·가전 켜고 끔(진짜 부위 API, 행동이 남는다). */
  private ResultActions toggle(UUID memberId, UUID partId, boolean enabled) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/parts/{partId}/toggle", room, partId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "toggle-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":" + enabled + "}"));
  }

  /** 부위 추가(진짜 부위 API). 새 부위 ID를 돌려준다. */
  private UUID addPart(UUID memberId, String name) throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms/{id}/parts", room)
                    .with(loginAs(memberId))
                    .header("Idempotency-Key", "add-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"zone\":\"방\",\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }

  private Timestamp firstShownAt() {
    return jdbc.queryForObject(
        "SELECT first_shown_at FROM state.repair_case WHERE room_record_id = ?",
        Timestamp.class,
        room);
  }
}
