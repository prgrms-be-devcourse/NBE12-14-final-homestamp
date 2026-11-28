package com.chilsuitjo.back.inspection.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import com.chilsuitjo.back.notification.dto.RequestTarget;
import com.chilsuitjo.back.notification.rule.RequestKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.FakeWaitService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 06 INSP-1a 입주 점검 시작·작성 중 항목·조회와 INSP-2b 입주일 저장, #190 호실 상세 입주일 숨김을 보안·pipeline·진짜 행동 기록
 * 저장(PostgreSQL)까지 띄워 본다. 사진은 가짜 사진 창구({@code FakePhotoService})가 받는다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import({TestcontainersConfiguration.class, InspectionApiTest.TestClock.class})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private FakeWaitService waits;
  @Autowired private MutableClock clock;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    clock.reset();
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = room(LocalDate.parse("2026-03-01"));
    tenant = member();
    landlord = member();
    part = part();
    seat(PartySide.TENANT, tenant, "CONNECTED");
    seat(PartySide.LANDLORD, landlord, "CONNECTED");
  }

  // ---- 시작 ----

  @Test
  void 시작하면_작성_중_점검과_행동이_함께_생기고_아직_상대에게_보이지_않는다() throws Exception {
    UUID id = created(start(tenant, "k1", "MOVE_IN", null));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", id);
    assertThat(row)
        .containsEntry("room_record_id", room)
        .containsEntry("kind", "MOVE_IN")
        .containsEntry("author_side", "TENANT")
        .containsEntry("status", "DRAFT")
        // 입주일(2026-03-01)보다 뒤에 시작해 늦은 입주 점검이다(INSP-2a)
        .containsEntry("late", true);
    assertThat(row.get("first_shown_at")).isNull();

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE inspection_id = ?", id);
    assertThat(action)
        .containsEntry("type", "START_INSPECTION")
        .containsEntry("actor_member_id", tenant)
        .containsEntry("actor_side", "TENANT");
    // 입주일이 이미 있는 호실이라 채운 입주일이 없다
    assertThat(action.get("payload").toString()).isEqualTo("{\"kind\": \"MOVE_IN\"}");
    assertThat(row.get("created_at")).isEqualTo(action.get("created_at"));
  }

  @Test
  void 같은_키로_다시_보내면_처음_점검을_돌려준다() throws Exception {
    UUID first = created(start(tenant, "same", "MOVE_IN", null));

    start(tenant, "same", "MOVE_IN", null)
        .andExpect(status().isCreated())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(count("state.inspection")).isEqualTo(1);
  }

  @Test
  void 상대가_먼저_시작했으면_INSPECTION_EXISTS() throws Exception {
    created(start(landlord, "a", "MOVE_IN", null));

    start(tenant, "b", "MOVE_IN", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSPECTION_EXISTS"));
    assertThat(
            jdbc.queryForObject(
                "SELECT author_side FROM state.inspection WHERE room_record_id = ?",
                String.class,
                room))
        .isEqualTo("LANDLORD");
  }

  @Test
  void 동시에_시작하면_한쪽만_기준이_되고_다른_쪽은_INSPECTION_EXISTS() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (UUID member : List.of(tenant, landlord)) {
        Callable<Integer> call =
            () -> {
              go.await();
              return start(member, "race-" + member, "MOVE_IN", null)
                  .andReturn()
                  .getResponse()
                  .getStatus();
            };
        results.add(pool.submit(call));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("state.inspection")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE room_record_id = ?"
                    + " AND type = 'START_INSPECTION'",
                Long.class,
                room))
        .isEqualTo(1);
  }

  @Test
  void 당사자가_아니면_NOT_FOUND() throws Exception {
    start(member(), "x", "MOVE_IN", null)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- 늦은 입주 점검(INSP-2a) ----

  @Test
  void INSP_2a_입주일보다_늦게_시작하면_상세에_late가_붙는다() throws Exception {
    UUID id = created(start(landlord, "late", "MOVE_IN", null));

    detail(landlord, id).andExpect(status().isOk()).andExpect(jsonPath("$.late").value(true));
  }

  @Test
  void INSP_2a_입주일이_아직_오지_않았으면_늦지_않다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET move_in_date = ? WHERE id = ?",
        Date.valueOf(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)),
        room);

    UUID id = created(start(tenant, "on-time", "MOVE_IN", null));

    assertThat(
            jdbc.queryForObject(
                "SELECT late FROM state.inspection WHERE id = ?", Boolean.class, id))
        .isFalse();
  }

  @Test
  void INSP_2a_INSP_2b_시작하며_채운_입주일로_늦었는지_정한다() throws Exception {
    clearMoveInDate();

    UUID id = created(start(tenant, "fill-late", "MOVE_IN", "2026-02-15"));

    assertThat(
            jdbc.queryForObject(
                "SELECT late FROM state.inspection WHERE id = ?", Boolean.class, id))
        .isTrue();
  }

  // ---- 퇴거 점검(INSP-3) ----

  @Test
  void INSP_3_퇴거_점검은_입주_점검과_따로_하나씩_시작한다() throws Exception {
    UUID moveIn = created(start(tenant, "in", "MOVE_IN", null));
    UUID moveOut = created(start(landlord, "out", "MOVE_OUT", null));

    assertThat(moveOut).isNotEqualTo(moveIn);
    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", moveOut);
    assertThat(row)
        .containsEntry("kind", "MOVE_OUT")
        .containsEntry("author_side", "LANDLORD")
        .containsEntry("status", "DRAFT")
        .containsEntry("late", false);
    start(tenant, "out-2", "MOVE_OUT", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSPECTION_EXISTS"));
    detail(landlord, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("MOVE_OUT"))
        .andExpect(jsonPath("$.late").value(false));
  }

  // ---- 나란히 보기(INSP-3b) ----

  @Test
  void INSP_3b_퇴거_점검은_같은_부위의_입주_항목_수리_건_퇴거_항목을_나란히_보이고_입주_기존_하자를_붙인다() throws Exception {
    UUID moveIn = created(start(tenant, "in", "MOVE_IN", null));
    UUID inItem = created(addItem(tenant, moveIn, "in-item", "타일 금", tickets(1)));
    UUID repair = created(registerRepair(tenant, "rep"));
    UUID moveOut = created(start(tenant, "out", "MOVE_OUT", null));
    UUID outItem = created(addItem(tenant, moveOut, "out-item", "타일 금 커짐", tickets(1)));

    detail(tenant, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide.moveIn.id").value(moveIn.toString()))
        .andExpect(jsonPath("$.sideBySide.moveIn.status").value("DRAFT"))
        .andExpect(jsonPath("$.sideBySide.parts.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].roomPartId").value(part.toString()))
        .andExpect(jsonPath("$.sideBySide.parts[0].partName").value("천장"))
        .andExpect(jsonPath("$.sideBySide.parts[0].mark").value("PREEXISTING"))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems[0].id").value(inItem.toString()))
        .andExpect(
            jsonPath("$.sideBySide.parts[0].moveInItems[0].version.description").value("타일 금"))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems[0].photos.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases[0].id").value(repair.toString()))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases[0].title").value("천장 누수"))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases[0].photos.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveOutItemIds[0]").value(outItem.toString()));
  }

  @Test
  void INSP_3b_입주_점검이_없고_수리_건만_있으면_거주_중_하자다() throws Exception {
    created(registerRepair(tenant, "rep"));
    UUID moveOut = created(start(landlord, "out", "MOVE_OUT", null));

    detail(landlord, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide.moveIn").doesNotExist())
        .andExpect(jsonPath("$.sideBySide.parts[0].mark").value("DURING_STAY"))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveInItems.length()").value(0))
        .andExpect(jsonPath("$.sideBySide.parts[0].moveOutItemIds.length()").value(0));
  }

  @Test
  void INSP_3b_퇴거_항목만_있는_부위는_표시하지_않고_기록이_없으면_부위도_없다() throws Exception {
    UUID moveOut = created(start(tenant, "out", "MOVE_OUT", null));
    detail(tenant, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide.parts.length()").value(0));

    created(addItem(tenant, moveOut, "out-item", "벽지 얼룩", tickets(0)));
    detail(tenant, moveOut)
        .andExpect(jsonPath("$.sideBySide.parts.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].mark").doesNotExist());
  }

  @Test
  void INSP_3b_불변식_18_공유를_그만둔_뒤_상대가_등록한_수리_건은_나란히_보기에서_빠진다() throws Exception {
    UUID before = created(registerRepair(landlord, "rep-before"));
    UUID moveOut = created(start(tenant, "out", "MOVE_OUT", null));
    stopSharing(PartySide.TENANT, clock.instant());
    daysPass(1);
    created(registerRepair(landlord, "rep-after"));

    detail(tenant, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide.parts.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases.length()").value(1))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases[0].id").value(before.toString()));
    detail(landlord, moveOut)
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases.length()").value(2))
        .andExpect(jsonPath("$.sideBySide.parts[0].repairCases[0].id").value(before.toString()));
  }

  @Test
  void INSP_3b_입주_점검_상세에는_나란히_보기가_없다() throws Exception {
    UUID moveIn = created(start(tenant, "in", "MOVE_IN", null));
    detail(tenant, moveIn)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide").doesNotExist());
  }

  @Test
  void INSP_3b_나란히_보기의_입주_항목은_입주_점검_상세와_같이_작성_중이면_상대에게_가린다() throws Exception {
    UUID moveIn = created(start(tenant, "in", "MOVE_IN", null));
    created(addItem(tenant, moveIn, "in-item", "타일 금", tickets(0)));
    UUID moveOut = created(start(landlord, "out", "MOVE_OUT", null));

    detail(landlord, moveOut)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sideBySide.moveIn.status").value("DRAFT"))
        .andExpect(jsonPath("$.sideBySide.parts.length()").value(0));
  }

  @Test
  void INSP_3a_거주_종료_뒤에도_퇴거_점검을_시작하고_확인_요청을_보낸다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    UUID id = created(start(tenant, "out-after-end", "MOVE_OUT", null));

    requestConfirm(tenant, id, "out-req")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  @Test
  void 퇴거_점검에_입주일을_보내면_VALIDATION_FAILED이고_입주일이_비어도_묻지_않는다() throws Exception {
    clearMoveInDate();

    start(tenant, "out-date", "MOVE_OUT", "2026-02-15")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    created(start(tenant, "out-no-date", "MOVE_OUT", null));
    assertThat(
            jdbc.queryForObject(
                "SELECT move_in_date FROM state.room_record WHERE id = ?", Date.class, room))
        .isNull();
  }

  @Test
  void 정산_완료_뒤에는_퇴거_점검을_시작할_수_없다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    start(tenant, "out-settled", "MOVE_OUT", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTLED"));
    assertThat(count("state.inspection")).isZero();
  }

  // ---- 입주일(INSP-2b) ----

  @Test
  void INSP_2b_입주일이_비었는데_보내지_않으면_MOVE_IN_DATE_REQUIRED() throws Exception {
    clearMoveInDate();

    start(tenant, "no-date", "MOVE_IN", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MOVE_IN_DATE_REQUIRED"));
    assertThat(count("state.inspection")).isZero();
    assertThat(count("record.action")).isZero();
  }

  @Test
  void INSP_2b_입주일을_함께_보내면_점검과_입주일이_함께_저장된다() throws Exception {
    clearMoveInDate();

    UUID id = created(start(tenant, "with-date", "MOVE_IN", "2026-02-15"));

    assertThat(moveInDate()).isEqualTo(LocalDate.parse("2026-02-15"));
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM record.action WHERE inspection_id = ?", String.class, id);
    assertThat(payload).contains("\"moveInDate\": \"2026-02-15\"");
  }

  @Test
  void 입주일이_이미_있는데_보내면_VALIDATION_FAILED이고_입주일은_그대로다() throws Exception {
    start(tenant, "change", "MOVE_IN", "2026-02-15")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(moveInDate()).isEqualTo(LocalDate.parse("2026-03-01"));
    assertThat(count("state.inspection")).isZero();
  }

  @Test
  void INSP_2b_입주일을_채운_시작을_같은_키로_다시_보내면_처음_결과를_돌려준다() throws Exception {
    // 다시 온 요청은 입주일이 이미 있어도 VALIDATION_FAILED가 아니라 처음과 같은 201이다(06 4-0)
    clearMoveInDate();
    UUID first = created(start(tenant, "date-again", "MOVE_IN", "2026-02-15"));

    start(tenant, "date-again", "MOVE_IN", "2026-02-15")
        .andExpect(status().isCreated())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.id").value(first.toString()));
    assertThat(moveInDate()).isEqualTo(LocalDate.parse("2026-02-15"));
    assertThat(count("state.inspection")).isEqualTo(1);
    assertThat(count("record.action")).isEqualTo(1);
  }

  @Test
  void 거주_종료_뒤에는_입주_점검을_새로_시작할_수_없다() throws Exception {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    start(tenant, "after-end", "MOVE_IN", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(count("state.inspection")).isZero();
    assertThat(count("record.action")).isZero();
  }

  // ---- 항목 더하기 ----

  @Test
  void 기준_측이_작성_중에_항목을_더하면_항목과_판_1과_행동이_함께_생긴다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", tickets(2)));

    Map<String, Object> head =
        jdbc.queryForMap("SELECT * FROM state.inspection_item WHERE id = ?", item);
    assertThat(head)
        .containsEntry("inspection_id", inspection)
        .containsEntry("status", "PENDING")
        .containsEntry("added_by_side", "TENANT")
        .containsEntry("added_after_lock", false)
        .containsEntry("added_after_end", false);
    Map<String, Object> version =
        jdbc.queryForMap(
            "SELECT * FROM record.inspection_item_version WHERE inspection_item_id = ?", item);
    assertThat(version)
        .containsEntry("id", head.get("current_version_id"))
        .containsEntry("no", 1)
        .containsEntry("room_part_id", part)
        .containsEntry("description", "벽지 찢어짐");

    Map<String, Object> action =
        jdbc.queryForMap("SELECT * FROM record.action WHERE inspection_item_id = ?", item);
    assertThat(action).containsEntry("type", "ADD_ITEM").containsEntry("actor_side", "TENANT");
    String payload = action.get("payload").toString();
    assertThat(payload).contains("\"inspectionId\": \"" + inspection + "\"").contains("\"photos\"");
    // 새 판을 만드는 행동이라 그 판 #1을 지문으로 남긴다(안내서 3-3). 칸 순서도 형식의 일부다.
    DigestInput expected =
        new DigestInput(
            "inspection_item_version",
            (UUID) version.get("id"),
            1,
            List.of(
                new DigestField("room_part_id", part.toString()),
                new DigestField("description", "벽지 찢어짐")));
    assertThat((byte[]) action.get("content_digest")).isEqualTo(ContentDigest.compute(expected));
  }

  @Test
  void 사진_없이도_항목을_더할_수_있다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    created(addItem(tenant, inspection, "no-photo", "창틀 곰팡이", "[]"));

    assertThat(count("state.inspection_item")).isEqualTo(1);
  }

  @Test
  void INSP_1d_상대가_작성_중에_더한_항목은_상대_본인에게만_보이고_기준_측_항목은_가린다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "mine", "벽지 찢어짐", "[]"));

    UUID theirs = created(addItem(landlord, inspection, "other", "방충망 찢어짐", "[]"));

    assertThat(jdbc.queryForMap("SELECT * FROM state.inspection_item WHERE id = ?", theirs))
        .containsEntry("added_by_side", "LANDLORD")
        .containsEntry("added_after_lock", false)
        .containsEntry("added_after_end", false);
    assertThat(inspectionRow(inspection)).containsEntry("status", "DRAFT");
    // 상대에게는 "작성 중"과 자기가 더한 항목만(06 INSP-1a, #286)
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(theirs.toString()));
    detail(tenant, inspection).andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  void 설명이_없거나_주민번호_형태면_거절한다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    addItem(tenant, inspection, "blank", " ", "[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    addItem(tenant, inspection, "rrn", "900101-1234567 얼룩", "[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RESIDENT_ID_BLOCKED"));
    assertThat(count("state.inspection_item")).isZero();
  }

  @Test
  void 남의_점검에는_항목을_더할_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    addItem(member(), inspection, "stranger", "얼룩", "[]")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- 조회(INSP-1a) ----

  @Test
  void INSP_1a_작성_중인_점검은_상대에게_작성_중만_보인다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.authorSide").value("TENANT"))
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.items").isEmpty())
        // 상대도 작성 중에 빠뜨린 하자를 더할 수 있다(#286)
        .andExpect(jsonPath("$.availableActions", contains("items")));
  }

  @Test
  void 기준_측은_작성_중인_항목과_부위_이름을_본다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(tenant, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("MOVE_IN"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].version.no").value(1))
        .andExpect(jsonPath("$.items[0].version.partName").value("천장"))
        .andExpect(jsonPath("$.items[0].version.description").value("벽지 찢어짐"))
        .andExpect(jsonPath("$.availableActions[0]").value("items"));
  }

  @Test
  void 당사자가_아니면_점검을_볼_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    detail(member(), inspection).andExpect(status().isNotFound());
    detail(tenant, UUID.randomUUID()).andExpect(status().isNotFound());
  }

  @Test
  void 항목이_없으면_빈_목록이고_항목_사진도_함께_보인다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    detail(tenant, inspection).andExpect(jsonPath("$.items").isEmpty());

    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", tickets(2)));

    detail(tenant, inspection)
        .andExpect(jsonPath("$.items[0].photos.length()").value(2))
        .andExpect(jsonPath("$.items[0].photos[0].stage").value("MOVE_IN"));
  }

  @Test
  void 공유를_그만둔_뒤_상대가_시작한_점검은_그만둔_사람에게_NOT_FOUND() throws Exception {
    Instant stopped = Instant.now().minusSeconds(3600);
    stopSharing(PartySide.LANDLORD, stopped);

    UUID inspection = created(start(tenant, "after-stop", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    detail(landlord, inspection).andExpect(status().isNotFound());
    // 남은 사람은 자기 점검과 항목을 그대로 보고, 보이는 범위를 고정한 시각을 받는다
    detail(tenant, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void 공유를_그만두기_전에_시작한_점검은_그만둔_사람도_볼_수_있다() throws Exception {
    UUID inspection = created(start(tenant, "before-stop", "MOVE_IN", null));
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  // ---- 확인 요청(INSP-1f, #218) ----

  @Test
  void 기준_측이_확인_요청을_보내면_상태와_요청과_처음_보인_시각이_함께_남고_상대가_항목을_본다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));

    requestConfirm(tenant, inspection, "r1")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(InspectionController.REPLAYED))
        .andExpect(jsonPath("$.id").value(inspection.toString()))
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    Map<String, Object> row = inspectionRow(inspection);
    assertThat(row).containsEntry("status", "REQUESTED");
    Map<String, Object> action =
        jdbc.queryForMap(
            "SELECT * FROM record.action WHERE inspection_id = ?"
                + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
            inspection);
    assertThat(action).containsEntry("actor_side", "TENANT");
    // 상대가 연결돼 있어 바로 보인다. 시각은 행동 시각과 같다(decisions 2026-11-23과 같은 기준)
    assertThat(row.get("first_shown_at")).isEqualTo(action.get("created_at"));
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()));
  }

  @Test
  void 항목이_없어도_하자_기록_없음으로_확인_요청을_보낸다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    requestConfirm(tenant, inspection, "r1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    detail(landlord, inspection).andExpect(jsonPath("$.items").isEmpty());
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);
  }

  @Test
  void 같은_키로_다시_보내면_처음_결과를_돌려주고_다른_키면_INVALID_STATE() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    requestConfirm(tenant, inspection, "same").andExpect(status().isOk());

    requestConfirm(tenant, inspection, "same")
        .andExpect(status().isOk())
        .andExpect(header().string(InspectionController.REPLAYED, "true"))
        .andExpect(jsonPath("$.status").value("REQUESTED"));
    requestConfirm(tenant, inspection, "again")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void INSP_1f_작성_중_7일_전에는_상대가_확인_요청을_보낼_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    daysPass(6);

    detail(landlord, inspection)
        .andExpect(jsonPath("$.counterpartCanRequestAt").exists())
        .andExpect(jsonPath("$.availableActions", contains("items")));
    requestConfirm(landlord, inspection, "early")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    assertThat(inspectionRow(inspection)).containsEntry("status", "DRAFT");
  }

  @Test
  void INSP_1f_작성_중_7일이_지나면_상대가_지금까지_쓴_것으로_보낸다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    daysPass(8);

    detail(landlord, inspection)
        .andExpect(jsonPath("$.availableActions", hasItem("request-confirm")));
    requestConfirm(landlord, inspection, "stale")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    // 누가 눌렀든 요청한 측은 기준 측이라 상대(집주인)의 답이 이 요청을 닫는다(#218 ①)
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);
    assertThat(confirmRequest(inspection, PartySide.LANDLORD)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_side FROM record.action WHERE inspection_id = ?"
                    + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
                String.class,
                inspection))
        .isEqualTo("LANDLORD");
    detail(landlord, inspection).andExpect(jsonPath("$.items[0].id").value(item.toString()));
  }

  @Test
  void 불변식_18_공유를_그만둔_뒤_보낸_확인_요청으로_작성_중_항목이_그만둔_사람에게_드러나지_않는다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    stopSharing(PartySide.LANDLORD, Instant.now());

    requestConfirm(tenant, inspection, "after-stop")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    // 그만둔 사람에게는 새 확인 요청을 만들지 않고 처음 보인 시각도 남기지 않는다(03 B, #218 ④)
    assertThat(confirmRequest(inspection, PartySide.TENANT)).isEmpty();
    assertThat(inspectionRow(inspection).get("first_shown_at")).isNull();
    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.items").isEmpty());
    // 남은 사람(기준 측)은 지금 상태를 본다
    detail(tenant, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void 불변식_18_기준_측이_공유를_그만둔_뒤_상대가_보낸_확인_요청은_기준_측에게_보이지_않는다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    daysPass(8);
    stopSharing(PartySide.TENANT, Instant.now());

    requestConfirm(landlord, inspection, "stale-after-stop").andExpect(status().isOk());

    assertThat(confirmRequest(inspection, PartySide.TENANT)).isEmpty();
    detail(tenant, inspection).andExpect(jsonPath("$.status").value("DRAFT"));
    // 보낸 상대에게는 자기 행동이라 상태는 확인 요청이지만, 기준 측이 그만둘 때 작성 중이던 항목은 드러나지 않는다(#218 리뷰)
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 불변식_18_공유를_그만두기_전에_보낸_확인_요청의_항목은_그만둔_뒤에도_보인다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    requestConfirm(tenant, inspection, "before-stop").andExpect(status().isOk());
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));

    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()));
  }

  @Test
  void 불변식_18_공유를_그만둔_시각과_같은_때_보낸_확인_요청은_보이고_그_직전에_그만뒀으면_가려진다() throws Exception {
    // 경계는 "그만둔 시각까지" 포함이다(행동 시각이 asOf보다 뒤일 때만 가림, #234 리뷰)
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    requestConfirm(tenant, inspection, "r1").andExpect(status().isOk());
    Instant requestedAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE inspection_id = ?"
                    + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
                Timestamp.class,
                inspection)
            .toInstant();

    stopSharing(PartySide.LANDLORD, requestedAt);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.asOf").exists())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()));

    stopSharing(PartySide.LANDLORD, requestedAt.minus(Duration.ofNanos(1_000)));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 불변식_18_항목을_더한_시각이_그만둔_시각과_같으면_보이고_그_뒤면_빠진다() throws Exception {
    // 지금은 작성 중에만 항목을 더해 API로는 만들 수 없는 순서라(확인 요청 뒤 더한 항목, INSP-1d·1e 후속) 항목 시각을 직접 옮겨 경계만 본다
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    requestConfirm(tenant, inspection, "r1").andExpect(status().isOk());
    Instant requestedAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE inspection_id = ?"
                    + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
                Timestamp.class,
                inspection)
            .toInstant();
    stopSharing(PartySide.LANDLORD, requestedAt);

    moveItemCreatedAt(item, requestedAt);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()));

    moveItemCreatedAt(item, requestedAt.plus(Duration.ofNanos(1_000)));
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 연결_전_상대에게도_확인_요청을_열고_처음_보인_시각은_비워_둔다() throws Exception {
    // #163 6-1: 03 1-4 "연결 전 상대" ① 링크로 알리고 기다린다. 점검은 연결할 때 보이게 된다(05 2-10)
    counterpartSeat("INVITED");
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    requestConfirm(tenant, inspection, "invited")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);
    assertThat(inspectionRow(inspection).get("first_shown_at")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"EMPTY", "NUMBER_ONLY", "REJECTED", "WITHDRAWN"})
  void 받을_상대가_없으면_상태만_바꾸고_요청은_열지_않는다(String seat) throws Exception {
    // #163 6-1: 상대 없음은 잠금 작업에서 canGoSoloDirectly로, 거절·탈퇴는 지금 열지 않는다
    counterpartSeat(seat);
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));

    requestConfirm(tenant, inspection, "no-counterpart")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    assertThat(confirmRequest(inspection, PartySide.TENANT)).isEmpty();
    assertThat(inspectionRow(inspection).get("first_shown_at")).isNull();
  }

  @Test
  void 확인_요청을_열다_실패하면_점검_상태와_행동_기록이_함께_되돌아간다() throws Exception {
    // #163 6-1: 요청 생성은 pipeline의 같은 트랜잭션 안(save)이라 실패하면 앞의 상태 변경·행동 기록도 남지 않는다
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    doThrow(new IllegalStateException("요청을 열 수 없어요")).when(waits).open(any());

    requestConfirm(tenant, inspection, "fail").andExpect(status().is5xxServerError());

    Map<String, Object> row = inspectionRow(inspection);
    assertThat(row).containsEntry("status", "DRAFT");
    assertThat(row.get("first_shown_at")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE inspection_id = ?"
                    + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
                Long.class,
                inspection))
        .isZero();
    assertThat(confirmRequest(inspection, PartySide.TENANT)).isEmpty();

    // 멱등 기록도 남지 않아 같은 키로 다시 보내면 처음 요청처럼 처리된다
    doCallRealMethod().when(waits).open(any());
    requestConfirm(tenant, inspection, "fail")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(InspectionController.REPLAYED))
        .andExpect(jsonPath("$.status").value("REQUESTED"));
    assertThat(confirmRequest(inspection, PartySide.TENANT)).contains(RequestStatus.WAITING);
  }

  @Test
  void 공유를_그만둔_사람은_확인_요청을_보낼_수_없다() throws Exception {
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    stopSharing(PartySide.TENANT, Instant.now());

    requestConfirm(tenant, inspection, "stopper")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    detail(tenant, inspection).andExpect(jsonPath("$.availableActions").isEmpty());
  }

  // ---- 호실 상세의 입주일(#190, 불변식 18) ----

  @Test
  void 공유를_그만둔_뒤_상대가_채운_입주일은_그만둔_사람에게_보이지_않는다() throws Exception {
    clearMoveInDate();
    stopSharing(PartySide.LANDLORD, Instant.now().minusSeconds(3600));

    created(start(tenant, "after-stop", "MOVE_IN", "2026-02-15"));

    roomDetail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").doesNotExist());
    // 채운 사람에게는 보인다
    roomDetail(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").value("2026-02-15"));
  }

  @Test
  void 공유를_그만두기_전에_채운_입주일은_그대로_보인다() throws Exception {
    clearMoveInDate();
    created(start(tenant, "before-stop", "MOVE_IN", "2026-02-15"));
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));

    roomDetail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.moveInDate").value("2026-02-15"));
  }

  @Test
  void 공유를_그만둔_뒤_상대가_채운_입주일과_시작한_점검은_상대가_탈퇴해도_보이지_않는다() throws Exception {
    // 상대가 탈퇴하면 보이는 범위는 고정하지 않지만(asOf 없음, 06 CONS-3p) 중단 동안 탈퇴자가 남긴 기록은 상대가 본 적이 없어
    // 계속 숨긴다(03 6-4, #288). 탈퇴는 sharing_stopped_at을 지우지 않는다(decisions 2026-11-26)
    clearMoveInDate();
    stopSharing(PartySide.LANDLORD, Instant.now().minusSeconds(3600));
    UUID inspection = created(start(tenant, "then-withdraw", "MOVE_IN", "2026-02-15"));
    withdraw(PartySide.TENANT);

    roomDetail(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.moveInDate").doesNotExist());
    detail(landlord, inspection).andExpect(status().isNotFound());
  }

  @Test
  void 공유를_그만두기_전에_상대가_채운_입주일은_상대가_탈퇴해도_보인다() throws Exception {
    clearMoveInDate();
    created(start(tenant, "before-withdraw", "MOVE_IN", "2026-02-15"));
    stopSharing(PartySide.LANDLORD, Instant.now().plusSeconds(3600));
    withdraw(PartySide.TENANT);

    roomDetail(landlord).andExpect(jsonPath("$.moveInDate").value("2026-02-15"));
  }

  @Test
  void 불변식_18_탈퇴자가_그만둔_뒤_보낸_확인_요청으로도_작성_중_항목이_드러나지_않는다() throws Exception {
    // 그만둘 때 작성 중이던 탈퇴자의 점검은 상대가 본 적이 없다(03 6-4 "작성 중 점검", #288). 상태 값은 지금 값이다(#314 한계)
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    stopSharing(PartySide.LANDLORD, Instant.now());
    requestConfirm(tenant, inspection, "after-stop").andExpect(status().isOk());
    withdraw(PartySide.TENANT);

    detail(landlord, inspection)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 불변식_18_상대가_탈퇴해도_항목은_남은_중단_시각까지_더한_것만_보인다() throws Exception {
    // 확인 요청 뒤 더한 항목은 API로 만들 수 없는 순서라 항목 시각을 옮겨 경계만 본다(위 그만둔 경우 테스트와 같음)
    UUID inspection = created(start(tenant, "s", "MOVE_IN", null));
    UUID item = created(addItem(tenant, inspection, "i1", "벽지 찢어짐", "[]"));
    requestConfirm(tenant, inspection, "r1").andExpect(status().isOk());
    Instant requestedAt =
        jdbc.queryForObject(
                "SELECT created_at FROM record.action WHERE inspection_id = ?"
                    + " AND type = 'REQUEST_INSPECTION_CONFIRM'",
                Timestamp.class,
                inspection)
            .toInstant();
    stopSharing(PartySide.LANDLORD, requestedAt);
    withdraw(PartySide.TENANT);

    moveItemCreatedAt(item, requestedAt);
    detail(landlord, inspection)
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.items[0].id").value(item.toString()));

    moveItemCreatedAt(item, requestedAt.plus(Duration.ofNanos(1_000)));
    detail(landlord, inspection).andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 공유를_그만둔_사람은_점검을_시작해_입주일을_채울_수_없다() throws Exception {
    // 그만둔 사람이 채운 입주일이 상대에게 드러나는 경우가 생기지 않는 이유: pipeline이 먼저 막는다(09 3-1 순서 3)
    clearMoveInDate();
    stopSharing(PartySide.LANDLORD, Instant.now().minusSeconds(3600));

    start(landlord, "stopper", "MOVE_IN", "2026-02-15")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT move_in_date FROM state.room_record WHERE id = ?", Date.class, room))
        .isNull();
    roomDetail(tenant).andExpect(jsonPath("$.moveInDate").doesNotExist());
  }

  // ---- 준비물 ----

  private ResultActions start(UUID member, String key, String kind, String moveInDate)
      throws Exception {
    String body =
        "{\"kind\":\""
            + kind
            + "\""
            + (moveInDate == null ? "" : ",\"moveInDate\":\"" + moveInDate + "\"")
            + "}";
    return mvc.perform(
        post("/api/rooms/{id}/inspections", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions addItem(
      UUID member, UUID inspection, String key, String description, String ticketsJson)
      throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\""
                    + description
                    + "\",\"uploadTickets\":"
                    + ticketsJson
                    + "}"));
  }

  private ResultActions registerRepair(UUID member, String key) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"title\":\"천장 누수\",\"uploadTickets\":"
                    + tickets(1)
                    + "}"));
  }

  private ResultActions detail(UUID member, UUID inspection) throws Exception {
    return mvc.perform(get("/api/inspections/{id}", inspection).with(loginAs(member)));
  }

  private ResultActions requestConfirm(UUID member, UUID inspection, String key) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/request-confirm", inspection)
            .with(loginAs(member))
            .header("Idempotency-Key", key));
  }

  /** 점검을 시작하고 며칠이 지난 것처럼 서버 시각을 옮긴다(점검 행과 행동 기록이 같은 시계를 쓴다). */
  private void daysPass(int days) {
    clock.advance(Duration.ofDays(days));
  }

  private Optional<RequestStatus> confirmRequest(UUID inspection, PartySide requestedBy) {
    return waits.statusOf(
        RequestTarget.of(TargetKind.INSPECTION, inspection),
        RequestKind.INSPECTION_CONFIRM,
        requestedBy);
  }

  private Map<String, Object> inspectionRow(UUID inspection) {
    return jdbc.queryForMap("SELECT * FROM state.inspection WHERE id = ?", inspection);
  }

  private ResultActions roomDetail(UUID member) throws Exception {
    return mvc.perform(get("/api/rooms/{id}", room).with(loginAs(member)));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private static String tickets(int count) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    return json.append(']').toString();
  }

  private UUID room(LocalDate moveInDate) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', ?, 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        Date.valueOf(moveInDate));
    return id;
  }

  private void clearMoveInDate() {
    jdbc.update("UPDATE state.room_record SET move_in_date = NULL WHERE id = ?", room);
  }

  private LocalDate moveInDate() {
    return jdbc.queryForObject(
            "SELECT move_in_date FROM state.room_record WHERE id = ?", Date.class, room)
        .toLocalDate();
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

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

  private void moveItemCreatedAt(UUID item, Instant at) {
    jdbc.update(
        "UPDATE state.inspection_item SET created_at = ? WHERE id = ?", Timestamp.from(at), item);
  }

  /** 임대인(상대) 자리를 회원 연결 없는 상태로 바꾼다. */
  private void counterpartSeat(String status) {
    jdbc.update(
        "UPDATE state.party SET status = ?, member_id = NULL"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        status,
        room);
  }

  /** 탈퇴 처리와 같게 자리만 탈퇴함으로 바꾼다(회원 ID·공유를 그만둔 시각은 남김, {@code Party.withdraw}). */
  private void withdraw(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', invite_phone = NULL"
            + " WHERE room_record_id = ? AND side = ?",
        room,
        side.name());
  }

  private void stopSharing(PartySide side, Instant at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(at),
        room,
        side.name());
  }

  private UUID part() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, (SELECT id FROM state.part_template ORDER BY sort_order LIMIT 1), '안방', '천장', 0,
               CURRENT_TIMESTAMP, 0
        """,
        id,
        room);
    return id;
  }

  private long count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Long.class, room);
  }

  /**
   * 서버 시각(pipeline·조회가 쓰는 {@code Clock}). 실제 시각에 옮긴 만큼을 더한 흐르는 시계다. 작성 중 기간이 지나는 것을 행동 기록 시각까지 함께
   * 옮겨 본다. 테스트마다 옮긴 값을 0으로 되돌린다.
   */
  static class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void reset() {
      offset.set(Duration.ZERO);
    }

    void advance(Duration duration) {
      offset.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(offset.get());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }
}
