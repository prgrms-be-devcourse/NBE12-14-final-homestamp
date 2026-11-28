package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
 * 06 4-3 {@code GET /api/rooms}(내 호실 목록)·{@code GET /api/rooms/{id}}(호실 상세)와 권한(ROOM-2a), 공유 중단 뒤
 * 보이는 범위(03 3-1, 불변식 18), {@code availableActions}·{@code nextStep}(06 4-0). 보안 설정까지 띄워 실제 액세스 JWT로
 * 부른다.
 *
 * <p>회원마다 새로 만들어 다른 테스트의 호실과 섞이지 않게 한다. 호실 ID는 만든 순서가 보이게 시각을 올려 가며 UUID v7로 만든다(목록은 ID 역순).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomQueryApiTest {

  private static final String REGION = "9999999993";

  /** 호실 ID의 시각. 테스트마다 앞으로만 간다. */
  private static final AtomicLong CLOCK = new AtomicLong(System.currentTimeMillis());

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID tenant;
  private UUID landlord;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '테스트시', '테스트구', '테스트동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    tenant = member();
    landlord = member();
  }

  // ---- 목록 ----

  @Test
  void 목록에는_내가_당사자인_호실만_새것부터_나온다() throws Exception {
    UUID first = room("첫 집", "TENANT");
    seat(first, "TENANT", tenant, "CONNECTED");
    seat(first, "LANDLORD", null, "EMPTY");
    UUID managed = room("관리 호실", "LANDLORD");
    seat(managed, "LANDLORD", tenant, "CONNECTED");
    seat(managed, "TENANT", landlord, "CONNECTED");
    UUID others = room("남의 집", "TENANT");
    seat(others, "TENANT", landlord, "CONNECTED");

    list(tenant, null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andExpect(jsonPath("$.items[0].id").value(managed.toString()))
        .andExpect(jsonPath("$.items[0].alias").value("관리 호실"))
        .andExpect(jsonPath("$.items[0].mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.items[0].counterpartStatus").value("CONNECTED"))
        .andExpect(jsonPath("$.items[1].id").value(first.toString()))
        .andExpect(jsonPath("$.items[1].region.code").value(REGION))
        .andExpect(jsonPath("$.items[1].region.sido").value("테스트시"))
        .andExpect(jsonPath("$.items[1].region.sigungu").value("테스트구"))
        .andExpect(jsonPath("$.items[1].region.dong").value("테스트동"))
        .andExpect(jsonPath("$.items[1].status").value("LIVING"))
        .andExpect(jsonPath("$.items[1].mySide").value("TENANT"))
        .andExpect(jsonPath("$.items[1].counterpartStatus").value("EMPTY"))
        .andExpect(jsonPath("$.items[1].joinedAway").value(false))
        .andExpect(jsonPath("$.items[1].nextStep").value("NONE"))
        .andExpect(jsonPath("$.items[1].createdAt").isNotEmpty())
        // 행동 목록은 상세에만 있다(06 4-0)
        .andExpect(jsonPath("$.items[1].availableActions").doesNotExist());
  }

  @Test
  void 호실이_없으면_빈_목록이다() throws Exception {
    list(member(), null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void 커서로_다음_쪽을_이어_받는다() throws Exception {
    UUID oldest = livingRoomOf(tenant);
    UUID middle = livingRoomOf(tenant);
    UUID newest = livingRoomOf(tenant);

    list(tenant, null, 2)
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(newest.toString()))
        .andExpect(jsonPath("$.items[1].id").value(middle.toString()))
        .andExpect(jsonPath("$.nextCursor").value(middle.toString()));

    list(tenant, middle.toString(), 2)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(oldest.toString()))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void 크기를_주지_않으면_20개씩이다() throws Exception {
    for (int i = 0; i < 21; i++) {
      livingRoomOf(tenant);
    }

    list(tenant, null, null)
        .andExpect(jsonPath("$.items.length()").value(20))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
  }

  @Test
  void 커서나_크기가_틀리면_VALIDATION_FAILED다() throws Exception {
    for (String query : new String[] {"size=0", "size=101", "size=abc", "cursor=not-a-uuid"}) {
      mvc.perform(get("/api/rooms?" + query).with(loginAs(tenant)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Test
  void 다음_할_일은_호실_상태와_내_공유_중단으로_정한다() throws Exception {
    UUID living = livingRoomOf(tenant);
    UUID ended = livingRoomOf(tenant);
    endRoom(ended);
    UUID settled = livingRoomOf(tenant);
    settleRoom(settled);
    UUID endedWhileStopped = livingRoomOf(tenant);
    endRoom(endedWhileStopped);
    stopSharing(endedWhileStopped, "TENANT", "2026-11-20T01:02:03Z");
    UUID joined = livingRoomOf(tenant);
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        living,
        joined);

    list(tenant, null, null)
        .andExpect(jsonPath("$.items[0].id").value(joined.toString()))
        .andExpect(jsonPath("$.items[0].joinedAway").value(true))
        .andExpect(jsonPath("$.items[0].nextStep").value("READ_ONLY"))
        // 공유를 그만둔 사람은 정산을 할 수 없다(03 B)
        .andExpect(jsonPath("$.items[1].nextStep").value("NONE"))
        .andExpect(jsonPath("$.items[2].status").value("SETTLED"))
        .andExpect(jsonPath("$.items[2].nextStep").value("READ_ONLY"))
        .andExpect(jsonPath("$.items[3].status").value("ENDED"))
        .andExpect(jsonPath("$.items[3].nextStep").value("SETTLE"))
        .andExpect(jsonPath("$.items[4].nextStep").value("NONE"));
  }

  @Test
  void 거주가_끝난_뒤_정산_차례는_공유_중단과_상대_탈퇴를_기존_정책대로_따른다() throws Exception {
    // 상대가 그만뒀으면 남은 사람은 끝내기만 막히고 항목·송금은 할 수 있다(CONS-3g)
    UUID counterpartStopped = connectedRoom();
    endRoom(counterpartStopped);
    stopSharing(counterpartStopped, "LANDLORD", "2026-11-20T01:02:03Z");
    // 내가 그만둔 뒤 상대가 탈퇴하면 내 제한이 풀린다(CONS-3p)
    UUID stoppedThenCounterpartLeft = connectedRoom();
    endRoom(stoppedThenCounterpartLeft);
    stopSharing(stoppedThenCounterpartLeft, "TENANT", "2026-11-20T01:02:03Z");
    withdraw(stoppedThenCounterpartLeft, "LANDLORD");
    // 그만둔 상대가 탈퇴하면 남은 사람은 혼자 끝낼 수 있다(CONS-3q)
    UUID stoppedCounterpartLeft = connectedRoom();
    endRoom(stoppedCounterpartLeft);
    stopSharing(stoppedCounterpartLeft, "LANDLORD", "2026-11-20T01:02:03Z");
    withdraw(stoppedCounterpartLeft, "LANDLORD");

    list(tenant, null, null)
        .andExpect(jsonPath("$.items[0].id").value(stoppedCounterpartLeft.toString()))
        .andExpect(jsonPath("$.items[0].nextStep").value("SETTLE"))
        .andExpect(jsonPath("$.items[1].id").value(stoppedThenCounterpartLeft.toString()))
        .andExpect(jsonPath("$.items[1].nextStep").value("SETTLE"))
        .andExpect(jsonPath("$.items[2].id").value(counterpartStopped.toString()))
        .andExpect(jsonPath("$.items[2].nextStep").value("SETTLE"));
    // 그만둔 쪽(상대가 탈퇴하지 않음)에게는 정산 차례를 주지 않는다
    list(landlord, null, null)
        .andExpect(jsonPath("$.items[0].id").value(counterpartStopped.toString()))
        .andExpect(jsonPath("$.items[0].nextStep").value("NONE"));
  }

  // ---- 상세 ----

  @Test
  void 만든_쪽은_요약과_상대_자리_상태와_누를_수_있는_행동을_받는다() throws Exception {
    UUID room = room("우리 집", "TENANT");
    jdbc.update("UPDATE state.room_record SET move_in_date = '2026-03-01' WHERE id = ?", room);
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", null, "EMPTY");

    detail(room, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(room.toString()))
        .andExpect(jsonPath("$.alias").value("우리 집"))
        .andExpect(jsonPath("$.region.dong").value("테스트동"))
        .andExpect(jsonPath("$.moveInDate").value("2026-03-01"))
        .andExpect(jsonPath("$.status").value("LIVING"))
        .andExpect(jsonPath("$.mySide").value("TENANT"))
        .andExpect(jsonPath("$.createdBySide").value("TENANT"))
        .andExpect(jsonPath("$.myStatus").value("CONNECTED"))
        .andExpect(jsonPath("$.counterpartStatus").value("EMPTY"))
        .andExpect(jsonPath("$.joinedAway").value(false))
        .andExpect(jsonPath("$.asOf").doesNotExist())
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.availableActions.length()").value(2))
        .andExpect(jsonPath("$.availableActions[0]").value("repair-cases"))
        .andExpect(jsonPath("$.availableActions[1]").value("inspections"))
        // 이름은 가리지 않은 값이든 가린 값이든 주지 않는다(이번 범위 밖, #190)
        .andExpect(jsonPath("$.counterpartName").doesNotExist())
        // 상대 번호가 없으면(비어 있음) 가린 번호도 없다
        .andExpect(jsonPath("$.counterpartMaskedPhone").doesNotExist());
  }

  @Test
  void 만든_쪽은_상대_자리에_저장한_번호를_가린_모양으로만_받는다() throws Exception {
    // 초대 화면에서 저장한 번호를 초대 전에 확인하게 한다(#407 리뷰, 03 "가린 번호만"). 원래 번호는 주지 않는다
    UUID room = room("번호만 호실", "TENANT");
    seat(room, "TENANT", tenant, "CONNECTED");
    seatWithPhone(room, "LANDLORD", "NUMBER_ONLY", "01012345678");

    detail(room, tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counterpartStatus").value("NUMBER_ONLY"))
        .andExpect(jsonPath("$.counterpartMaskedPhone").value("010-****-5678"))
        .andExpect(content().string(not(containsString("01012345678"))));
  }

  @Test
  void 초대됨과_거절됨에서도_남아_있는_번호를_가린_모양으로_받는다() throws Exception {
    UUID invited = room("초대한 호실", "LANDLORD");
    seat(invited, "LANDLORD", landlord, "CONNECTED");
    seatWithPhone(invited, "TENANT", "INVITED", "0111234567");
    UUID rejected = room("거절된 호실", "LANDLORD");
    seat(rejected, "LANDLORD", landlord, "CONNECTED");
    seatWithPhone(rejected, "TENANT", "REJECTED", "01099998888");

    detail(invited, landlord).andExpect(jsonPath("$.counterpartMaskedPhone").value("011-***-4567"));
    detail(rejected, landlord)
        .andExpect(jsonPath("$.counterpartMaskedPhone").value("010-****-8888"));
  }

  @Test
  void 상대_측_회원은_자기_측으로_본다() throws Exception {
    UUID room = connectedRoom();

    detail(room, landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mySide").value("LANDLORD"))
        .andExpect(jsonPath("$.createdBySide").value("TENANT"))
        .andExpect(jsonPath("$.myStatus").value("CONNECTED"))
        .andExpect(jsonPath("$.counterpartStatus").value("CONNECTED"));
  }

  @Test
  void 남의_호실과_없는_호실은_같은_NOT_FOUND다() throws Exception {
    UUID room = connectedRoom();
    UUID stranger = member();

    detail(room, stranger)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    detail(UUID.randomUUID(), stranger)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 상대가_초대를_거절하면_상대_자리_상태로_보인다() throws Exception {
    UUID room = room("거절된 호실", "TENANT");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", null, "REJECTED");

    detail(room, tenant).andExpect(jsonPath("$.counterpartStatus").value("REJECTED"));
    list(tenant, null, null).andExpect(jsonPath("$.items[0].counterpartStatus").value("REJECTED"));
  }

  @Test
  void 공유를_그만두면_양쪽_모두_그_시각을_asOf로_받는다() throws Exception {
    UUID room = connectedRoom();
    stopSharing(room, "LANDLORD", "2026-11-20T01:02:03Z");

    detail(room, landlord)
        .andExpect(jsonPath("$.myStatus").value("SHARING_STOPPED"))
        .andExpect(jsonPath("$.asOf").value("2026-11-20T01:02:03Z"))
        // 그만둔 사람도 새 하자는 나만 보는 기록으로 남길 수 있다(03 B)
        .andExpect(jsonPath("$.availableActions[0]").value("repair-cases"));
    detail(room, tenant)
        .andExpect(jsonPath("$.counterpartStatus").value("SHARING_STOPPED"))
        .andExpect(jsonPath("$.asOf").value("2026-11-20T01:02:03Z"));
  }

  @Test
  void 둘_다_그만두면_이른_시각이_asOf다() throws Exception {
    UUID room = connectedRoom();
    stopSharing(room, "LANDLORD", "2026-11-20T01:02:03Z");
    stopSharing(room, "TENANT", "2026-11-21T00:00:00Z");

    detail(room, tenant).andExpect(jsonPath("$.asOf").value("2026-11-20T01:02:03Z"));
  }

  @Test
  void 상대가_탈퇴하면_공유_중단으로_고정하지_않는다() throws Exception {
    UUID room = connectedRoom();
    stopSharing(room, "TENANT", "2026-11-20T01:02:03Z");
    withdraw(room, "LANDLORD");

    detail(room, tenant)
        .andExpect(jsonPath("$.counterpartStatus").value("WITHDRAWN"))
        .andExpect(jsonPath("$.asOf").doesNotExist());
  }

  @Test
  void 거주_종료_정산_완료_합류_전_기록에서는_하자_등록이_없고_실제_요청도_같은_이유로_막힌다() throws Exception {
    UUID ended = connectedRoom();
    endRoom(ended);
    UUID settled = connectedRoom();
    settleRoom(settled);
    UUID joined = livingRoomOf(tenant);
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        ended,
        joined);

    detail(ended, tenant)
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    register(ended, tenant).andExpect(jsonPath("$.code").value("ROOM_ENDED"));

    detail(settled, landlord)
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    register(settled, landlord).andExpect(jsonPath("$.code").value("SETTLED"));

    detail(joined, tenant)
        .andExpect(jsonPath("$.joinedAway").value(true))
        .andExpect(jsonPath("$.availableActions").isEmpty());
    register(joined, tenant).andExpect(jsonPath("$.code").value("JOINED_RECORD"));
  }

  @Test
  void 하자_등록이_보이면_실제_요청도_된다() throws Exception {
    UUID room = connectedRoom();
    stopSharing(room, "LANDLORD", "2026-11-20T01:02:03Z");

    detail(room, landlord).andExpect(jsonPath("$.availableActions[0]").value("repair-cases"));
    register(room, landlord).andExpect(status().isCreated());
  }

  // ---- 입주 점검 시작(inspections, #357) ----

  @Test
  void 입주_점검_시작이_보이면_실제_시작도_되고_시작한_뒤에는_양쪽_모두_사라진다() throws Exception {
    UUID room = connectedRoom();

    detail(room, tenant).andExpect(jsonPath("$.availableActions", hasItem("inspections")));
    detail(room, landlord).andExpect(jsonPath("$.availableActions", hasItem("inspections")));
    startMoveIn(room, tenant, "2026-03-01").andExpect(status().isCreated());

    // 상대가 작성 중이어도 점검은 호실에 하나라 시작할 수 없다(03 H "입주 점검 기준 기록")
    detail(room, tenant).andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    detail(room, landlord).andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    startMoveIn(room, landlord, null).andExpect(jsonPath("$.code").value("INSPECTION_EXISTS"));
  }

  @Test
  void 거주_종료_정산_완료_합류_전_기록에서는_입주_점검_시작이_없고_실제_요청도_막힌다() throws Exception {
    UUID ended = connectedRoom();
    endRoom(ended);
    UUID settled = connectedRoom();
    settleRoom(settled);
    UUID joined = livingRoomOf(tenant);
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        ended,
        joined);

    detail(ended, tenant).andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    startMoveIn(ended, tenant, "2026-03-01").andExpect(status().is4xxClientError());
    detail(settled, landlord)
        .andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    startMoveIn(settled, landlord, "2026-03-01").andExpect(status().is4xxClientError());
    detail(joined, tenant).andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    startMoveIn(joined, tenant, "2026-03-01").andExpect(status().is4xxClientError());
  }

  @Test
  void 공유를_그만둔_뒤_상대가_시작한_입주_점검이_있으면_시작이_없고_실제_요청도_막힌다() throws Exception {
    UUID room = connectedRoom();
    stopSharing(room, "LANDLORD", "2026-11-20T01:02:03Z");
    startMoveIn(room, tenant, "2026-03-01").andExpect(status().isCreated());

    detail(room, landlord).andExpect(jsonPath("$.availableActions", not(hasItem("inspections"))));
    startMoveIn(room, landlord, null).andExpect(status().is4xxClientError());
  }

  @Test
  void 공유를_그만둔_사람의_입주_점검_시작은_실제_요청과_같다() throws Exception {
    UUID room = connectedRoom();
    jdbc.update("UPDATE state.room_record SET move_in_date = '2026-03-01' WHERE id = ?", room);
    stopSharing(room, "LANDLORD", "2026-11-20T01:02:03Z");

    boolean shown =
        detail(room, landlord)
            .andReturn()
            .getResponse()
            .getContentAsString()
            .contains("\"inspections\"");
    int status = startMoveIn(room, landlord, null).andReturn().getResponse().getStatus();

    assertThat(status == 201).isEqualTo(shown);
  }

  @Test
  void 로그인하지_않으면_볼_수_없다() throws Exception {
    UUID room = connectedRoom();

    mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/rooms/{id}", room)).andExpect(status().isUnauthorized());
  }

  // ---- 준비물 ----

  private ResultActions list(UUID memberId, String cursor, Integer size) throws Exception {
    var request = get("/api/rooms").with(loginAs(memberId));
    if (cursor != null) {
      request.param("cursor", cursor);
    }
    if (size != null) {
      request.param("size", size.toString());
    }
    return mvc.perform(request);
  }

  private ResultActions detail(UUID roomId, UUID memberId) throws Exception {
    return mvc.perform(get("/api/rooms/{id}", roomId).with(loginAs(memberId)));
  }

  /**
   * 하자 등록(실제 요청). 부위를 하나 만들어 쓴다. 호실을 만들 때 기본 목록에서 복사된 부위처럼 템플릿을 가리킨다: 공유를 그만둔 뒤 하자 등록은 등록하는 사람에게
   * 보이는 부위 상태로 판단하는데(#183), 템플릿도 {@code ADD_PART}도 없는 부위는 실제 호실에 없는 모양이라 보이지 않는다.
   */
  private ResultActions register(UUID roomId, UUID memberId) throws Exception {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone <> '설비·가전' ORDER BY t.sort_order LIMIT 1
        """,
        part,
        roomId);
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", roomId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "reg-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\","
                    + "\"uploadTickets\":[\""
                    + UUID.randomUUID()
                    + "\"]}"));
  }

  /** 입주 점검 시작(실제 요청). 입주일이 비어 있는 호실이면 {@code moveInDate}를 준다(06 INSP-2b). */
  private ResultActions startMoveIn(UUID roomId, UUID memberId, String moveInDate)
      throws Exception {
    String date = moveInDate == null ? "" : ",\"moveInDate\":\"" + moveInDate + "\"";
    return mvc.perform(
        post("/api/rooms/{id}/inspections", roomId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "insp-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"MOVE_IN\"" + date + "}"));
  }

  /** 세입자가 만들고 집주인이 연결된 거주 중인 호실. */
  private UUID connectedRoom() {
    UUID room = room("함께 쓰는 호실", "TENANT");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    return room;
  }

  /** 이 회원이 세입자로 만든, 상대 자리가 빈 거주 중인 호실. */
  private UUID livingRoomOf(UUID memberId) {
    UUID room = room("호실", "TENANT");
    seat(room, "TENANT", memberId, "CONNECTED");
    seat(room, "LANDLORD", null, "EMPTY");
    return room;
  }

  private UUID room(String alias, String createdBySide) {
    UUID id = v7(CLOCK.incrementAndGet());
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'LIVING', ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        alias,
        createdBySide);
    return id;
  }

  private void seat(UUID room, String side, UUID memberId, String status) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side,
        memberId,
        status);
  }

  private void seatWithPhone(UUID room, String side, String status, String invitePhone) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side,
        invitePhone,
        status);
  }

  private void stopSharing(UUID room, String side, String at) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?::timestamptz"
            + " WHERE room_record_id = ? AND side = ?",
        at,
        room,
        side);
  }

  /** 그 측 회원이 탈퇴했다. 공유 중단 시각은 남아 있어도 보지 않는다(03 B). */
  private void withdraw(UUID room, String side) {
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', member_id = NULL"
            + " WHERE room_record_id = ? AND side = ?",
        room,
        side);
  }

  private void endRoom(UUID room) {
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
  }

  private void settleRoom(UUID room) {
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  /** 이 시각의 UUID v7. 목록 순서(ID 역순)를 정하려고 시각을 직접 준다. */
  private static UUID v7(long epochMillis) {
    long msb = (epochMillis << 16) | 0x7000L;
    long lsb =
        (UUID.randomUUID().getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL) | Long.MIN_VALUE;
    return new UUID(msb, lsb);
  }
}
