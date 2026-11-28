package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
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

/**
 * 호실 상세(06 4-3 {@code GET /api/rooms/{id}})의 거주 종료 칸과 행동(#445, ROOM-9). 행동 이름은 API 끝 단어 {@code
 * move-out}·{@code confirm}·{@code reject}이고 실제 요청과 같은 판단이다. 활성화 설정({@code
 * app.room.end.actions-enabled})을 켠 채로 본다. 꺼진 기본값은 {@link RoomEndDetailDisabledApiTest}가 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {"test.notification.wait-service=jpa", "app.room.end.actions-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomEndDetailApiTest {

  static final String REGION = "9799999903";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID tenant;
  private UUID landlord;

  @BeforeEach
  void setUp() {
    region(jdbc);
    tenant = member(jdbc);
    landlord = member(jdbc);
  }

  @Test
  void 거주_중이면_세입자는_이사했어요_집주인은_이사함을_누를_수_있고_요청은_없다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "CONNECTED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");

    detail(mvc, room, tenant)
        .andExpect(jsonPath("$.availableActions", hasItem("move-out")))
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm"))))
        .andExpect(jsonPath("$.endRequest").isEmpty())
        .andExpect(jsonPath("$.endedAt").isEmpty())
        .andExpect(jsonPath("$.endSoloReason").isEmpty());
    detail(mvc, room, landlord).andExpect(jsonPath("$.availableActions", hasItem("move-out")));
  }

  @Test
  void ROOM_9b_요청이_열리면_세입자는_답하고_집주인은_다시_보내지_못한다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "CONNECTED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    moveOut(mvc, room, landlord).andExpect(status().isOk());

    detail(mvc, room, tenant)
        .andExpect(jsonPath("$.availableActions", hasItem("move-out")))
        .andExpect(jsonPath("$.availableActions", hasItem("confirm")))
        .andExpect(jsonPath("$.availableActions", hasItem("reject")))
        .andExpect(jsonPath("$.endRequest.status").value("WAITING"))
        .andExpect(jsonPath("$.endRequest.openedAt").isNotEmpty())
        // 거주 종료 요청 문자가 아직 없어 기한이 정해지지 않았다(NOTI-4b, #415)
        .andExpect(jsonPath("$.endRequest.dueAt").isEmpty())
        .andExpect(jsonPath("$.endRequest.rejectReason").isEmpty());
    detail(mvc, room, landlord)
        .andExpect(jsonPath("$.availableActions", not(hasItem("move-out"))))
        .andExpect(jsonPath("$.availableActions", not(hasItem("confirm"))))
        .andExpect(jsonPath("$.endRequest.status").value("WAITING"));
  }

  @Test
  void ROOM_9b_아직_살고_있어요면_사유가_양쪽에_보이고_집주인은_다시_요청할_수_있다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "CONNECTED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    moveOut(mvc, room, landlord).andExpect(status().isOk());
    mvc.perform(
            post("/api/rooms/{id}/move-out/reject", room)
                .with(loginAs(tenant))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"다음 달 15일에 이사해요\"}"))
        .andExpect(status().isOk());

    for (UUID viewer : new UUID[] {landlord, tenant}) {
      detail(mvc, room, viewer)
          .andExpect(jsonPath("$.status").value("LIVING"))
          .andExpect(jsonPath("$.endRequest.status").value("ANSWERED"))
          .andExpect(jsonPath("$.endRequest.rejectReason").value("다음 달 15일에 이사해요"))
          .andExpect(jsonPath("$.availableActions", not(hasItem("confirm"))));
    }
    detail(mvc, room, landlord).andExpect(jsonPath("$.availableActions", hasItem("move-out")));
  }

  @Test
  void ROOM_9d_공유를_그만둔_세입자도_요청을_보고_답할_수_있고_집주인도_요청을_본다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "SHARING_STOPPED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    moveOut(mvc, room, landlord).andExpect(status().isOk());

    detail(mvc, room, tenant)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.availableActions", hasItem("confirm")))
        .andExpect(jsonPath("$.availableActions", hasItem("reject")))
        .andExpect(jsonPath("$.endRequest.status").value("WAITING"));
    detail(mvc, room, landlord)
        .andExpect(jsonPath("$.asOf").isNotEmpty())
        .andExpect(jsonPath("$.endRequest.status").value("WAITING"));
  }

  @Test
  void 세입자가_맞아요면_거주_종료_날짜가_양쪽에_보이고_거주_종료_행동은_없다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "CONNECTED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    moveOut(mvc, room, landlord).andExpect(status().isOk());
    mvc.perform(
            post("/api/rooms/{id}/move-out/confirm", room)
                .with(loginAs(tenant))
                .header("Idempotency-Key", key()))
        .andExpect(status().isOk());

    for (UUID viewer : new UUID[] {landlord, tenant}) {
      detail(mvc, room, viewer)
          .andExpect(jsonPath("$.status").value("ENDED"))
          .andExpect(jsonPath("$.endedAt").isNotEmpty())
          .andExpect(jsonPath("$.endSoloReason").isEmpty())
          .andExpect(jsonPath("$.endRequest.status").value("ANSWERED"))
          .andExpect(jsonPath("$.availableActions", not(hasItem("move-out"))))
          .andExpect(jsonPath("$.availableActions", not(hasItem("confirm"))));
    }
  }

  @Test
  void 공유를_그만둔_세입자의_이사했어요도_집주인에게_거주_종료_날짜로_보인다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", tenant, "SHARING_STOPPED");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    moveOut(mvc, room, tenant).andExpect(status().isOk());

    detail(mvc, room, landlord)
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.endedAt").isNotEmpty());
  }

  @Test
  void 물을_세입자가_없어_혼자_거주_종료하면_그_이유가_보인다() throws Exception {
    UUID room = room(jdbc, "LIVING");
    seat(jdbc, room, "TENANT", null, "NUMBER_ONLY");
    seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
    detail(mvc, room, landlord).andExpect(jsonPath("$.availableActions", hasItem("move-out")));

    moveOut(mvc, room, landlord).andExpect(status().isOk());

    detail(mvc, room, landlord)
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.endSoloReason").value("NO_COUNTERPART"))
        .andExpect(jsonPath("$.endRequest").isEmpty());
  }

  @Test
  void 세입자_자리가_연결_전_거절_탈퇴이면_집주인에게_이사함이_없다() throws Exception {
    // 실제 요청이 INVALID_STATE인 동안(#415)은 버튼도 없다. #428·#421 댓글 3번이 판단을 바꾸면 같은 함수라 함께 바뀐다
    for (String seatStatus : new String[] {"INVITED", "REJECTED", "WITHDRAWN"}) {
      UUID room = room(jdbc, "LIVING");
      seat(jdbc, room, "TENANT", null, seatStatus);
      seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
      detail(mvc, room, landlord)
          .andExpect(jsonPath("$.availableActions", not(hasItem("move-out"))));
    }
  }

  @Test
  void 거주_종료나_정산_완료_호실에는_거주_종료_행동이_없다() throws Exception {
    for (String roomStatus : new String[] {"ENDED", "SETTLED"}) {
      UUID room = room(jdbc, roomStatus);
      seat(jdbc, room, "TENANT", tenant, "CONNECTED");
      seat(jdbc, room, "LANDLORD", landlord, "CONNECTED");
      detail(mvc, room, tenant).andExpect(jsonPath("$.availableActions", empty()));
      detail(mvc, room, landlord).andExpect(jsonPath("$.availableActions", empty()));
    }
  }

  // ---- 도우미(RoomEndDetailDisabledApiTest와 같이 씀) ----

  static ResultActions detail(MockMvc mvc, UUID room, UUID memberId) throws Exception {
    return mvc.perform(get("/api/rooms/{id}", room).with(loginAs(memberId)))
        .andExpect(status().isOk());
  }

  static ResultActions moveOut(MockMvc mvc, UUID room, UUID memberId) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/move-out", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key()));
  }

  static void region(JdbcTemplate jdbc) {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '종료시', '종료구', '종료동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
  }

  static UUID room(JdbcTemplate jdbc, String roomStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, ended_at, settled_at, created_at, version)
        VALUES (?, ?, '302호', ?, 'TENANT',
          CASE WHEN CAST(? AS varchar) = 'LIVING' THEN NULL ELSE CURRENT_TIMESTAMP END,
          CASE WHEN CAST(? AS varchar) = 'SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        roomStatus,
        roomStatus,
        roomStatus);
    return id;
  }

  static UUID seat(JdbcTemplate jdbc, UUID room, String side, UUID memberId, String seatStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, connected_at,
           sharing_stopped_at, created_at, version)
        VALUES (?, ?, ?, ?,
          CASE WHEN CAST(? AS varchar) IN ('NUMBER_ONLY', 'INVITED', 'REJECTED') THEN '01012345678' END,
          ?,
          CASE WHEN CAST(? AS varchar) IN ('CONNECTED', 'SHARING_STOPPED') THEN CURRENT_TIMESTAMP END,
          CASE WHEN CAST(? AS varchar) = 'SHARING_STOPPED' THEN CURRENT_TIMESTAMP END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        seatStatus,
        seatStatus,
        seatStatus,
        seatStatus);
    return id;
  }

  static UUID member(JdbcTemplate jdbc) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, status, created_at, version)
        VALUES (?, '김하늘', 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  static String key() {
    return UUID.randomUUID().toString();
  }
}
