package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.rule.LinkToken;
import java.sql.Timestamp;
import java.time.Duration;
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
 * 06 4-3 {@code POST /api/rooms/{id}/move-out}·{@code /move-out/confirm}·{@code
 * /move-out/reject}(ROOM-9a~d). 보안 설정·pipeline·실제 {@code JpaWaitService}·초대 링크 무효화까지 띄워 부른다. 거주 종료
 * 안내 알림·거주 종료 요청 문자·미확인 뒤 적용은 아직 없다(#415 남은 작업).
 *
 * <p>닫는 범위(#370 B-1)를 DB 행으로 보려고 가짜 {@code FakeWaitService} 대신 실제 {@code JpaWaitService}를 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.notification.wait-service=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomEndApiTest {

  private static final String REGION = "9799999902";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID tenant;
  private UUID landlord;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '종료시', '종료구', '종료동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    tenant = member();
    landlord = member();
  }

  // ---- 세입자 측 [이사했어요] ----

  @Test
  void ROOM_9a_세입자가_이사했어요를_누르면_바로_거주_종료이고_기한이_남는다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");

    moveOut(room, tenant, key())
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()))
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.endRequested").value(false));

    Map<String, Object> row = roomRow(room);
    assertThat(row.get("status")).isEqualTo("ENDED");
    Timestamp endedAt = (Timestamp) row.get("ended_at");
    List<Map<String, Object>> actions = actions(room);
    assertThat(actions).extracting(a -> a.get("type")).containsExactly("MOVE_OUT");
    assertThat(actions.getFirst().get("actor_side")).isEqualTo("TENANT");
    assertThat(actions.getFirst().get("solo_reason")).isNull();
    // 종료 시각은 행동 시각, 자동 마감은 그 90일 뒤(06 0-1 settlement.autoclose-after)
    assertThat(endedAt).isEqualTo(actions.getFirst().get("created_at"));
    assertThat(((Timestamp) row.get("autoclose_due_at")).toInstant())
        .isEqualTo(endedAt.toInstant().plus(Duration.ofDays(90)));
  }

  @Test
  void ROOM_9a_거주_종료_때_초대_링크가_닫히고_다른_호실_링크는_그대로다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    UUID landlordSeat = seat(room, "LANDLORD", null, "INVITED");
    UUID mine = inviteLink(room, landlordSeat);
    UUID other = room("LIVING");
    seat(other, "TENANT", member(), "CONNECTED");
    UUID otherLink = inviteLink(other, seat(other, "LANDLORD", null, "INVITED"));

    moveOut(room, tenant, key()).andExpect(status().isOk());

    assertThat(linkReason(mine)).isEqualTo("RESIDENCE_ENDED");
    assertThat(linkReason(otherLink)).isNull();
  }

  @Test
  void ROOM_9a_B_1_범위의_요청만_거주_종료로_닫힌다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    UUID defectWaiting = request(room, "DEFECT_CONFIRM", repair(room), "WAITING");
    UUID defectUnconfirmed = request(room, "DEFECT_CONFIRM", repair(room), "UNCONFIRMED");
    UUID progressWaiting = request(room, "PROGRESS_CHECK", repair(room), "WAITING");
    UUID closeWaiting = request(room, "CLOSE_CONFIRM", repair(room), "WAITING");
    UUID closeUnconfirmed = request(room, "CLOSE_CONFIRM", repair(room), "UNCONFIRMED");
    UUID correction = request(room, "CORRECTION_CONFIRM", repair(room), "WAITING");
    UUID answered = request(room, "DEFECT_CONFIRM", repair(room), "ANSWERED");
    UUID other = room("LIVING");
    UUID otherDefect = request(other, "DEFECT_CONFIRM", repair(other), "WAITING");

    moveOut(room, tenant, key()).andExpect(status().isOk());

    Object endedAt = roomRow(room).get("ended_at");
    for (UUID closed : List.of(defectWaiting, defectUnconfirmed, progressWaiting, closeWaiting)) {
      Map<String, Object> r = requestRow(closed);
      assertThat(r.get("status")).isEqualTo("CLOSED");
      assertThat(r.get("closed_reason")).isEqualTo("ROOM_ENDED");
      assertThat(r.get("closed_at")).isEqualTo(endedAt);
    }
    // 미확인 종료 확인과 고친 내용 확인·답한 요청·다른 호실은 그대로(#370 B-1)
    assertThat(requestRow(closeUnconfirmed).get("status")).isEqualTo("UNCONFIRMED");
    assertThat(requestRow(correction).get("status")).isEqualTo("WAITING");
    assertThat(requestRow(answered).get("status")).isEqualTo("ANSWERED");
    assertThat(requestRow(otherDefect).get("status")).isEqualTo("WAITING");
  }

  @Test
  void ROOM_9c_공유를_그만둔_세입자도_이사했어요를_남길_수_있다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "SHARING_STOPPED");
    seat(room, "LANDLORD", landlord, "CONNECTED");

    moveOut(room, tenant, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"));
  }

  @Test
  void 거주_종료_요청이_열린_채_세입자가_이사했어요를_누르면_요청은_거주_종료로_닫힌다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    moveOut(room, landlord, key()).andExpect(status().isOk());

    moveOut(room, tenant, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.endRequested").value(false));

    Map<String, Object> request = endRequest(room);
    assertThat(request.get("status")).isEqualTo("CLOSED");
    assertThat(request.get("closed_reason")).isEqualTo("ROOM_ENDED");
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려주고_다른_키로는_INVALID_STATE다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    String key = key();
    moveOut(room, tenant, key).andExpect(status().isOk());

    moveOut(room, tenant, key)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("ENDED"));
    moveOut(room, tenant, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertThat(actions(room)).hasSize(1);
  }

  // ---- 집주인 측 [이사함] ----

  @Test
  void ROOM_9b_집주인이_이사함을_누르면_거주_종료_요청이_가고_거주_중이다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");

    moveOut(room, landlord, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVING"))
        .andExpect(jsonPath("$.endRequested").value(true));

    assertThat(roomRow(room).get("status")).isEqualTo("LIVING");
    assertThat(actions(room)).extracting(a -> a.get("type")).containsExactly("REQUEST_END");
    Map<String, Object> request = endRequest(room);
    assertThat(request.get("status")).isEqualTo("WAITING");
    assertThat(request.get("requested_by_side")).isEqualTo("LANDLORD");
    // 거주 종료 요청은 호실 기록 자체가 대상이다
    assertThat(request.get("repair_case_id")).isNull();
  }

  @Test
  void 거주_종료_요청은_한_번만_보내고_같은_키면_처음_결과다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    String key = key();
    moveOut(room, landlord, key).andExpect(status().isOk());

    moveOut(room, landlord, key)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.endRequested").value(true));
    moveOut(room, landlord, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertThat(actions(room)).hasSize(1);
    assertThat(jdbc.queryForObject(countEndRequests(), Integer.class, room)).isEqualTo(1);
  }

  @Test
  void ROOM_9d_세입자가_공유를_그만뒀어도_바로_종료하지_않고_요청이_간다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "SHARING_STOPPED");
    seat(room, "LANDLORD", landlord, "CONNECTED");

    moveOut(room, landlord, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVING"))
        .andExpect(jsonPath("$.endRequested").value(true));

    assertThat(endRequest(room).get("status")).isEqualTo("WAITING");
  }

  @Test
  void 물을_세입자가_없으면_집주인_측도_바로_거주_종료이고_혼자_진행_이유를_남긴다() throws Exception {
    for (String seatStatus : new String[] {"EMPTY", "NUMBER_ONLY"}) {
      UUID room = room("LIVING");
      seat(room, "TENANT", null, seatStatus);
      seat(room, "LANDLORD", landlord, "CONNECTED");

      moveOut(room, landlord, key())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("ENDED"))
          .andExpect(jsonPath("$.endRequested").value(false));

      List<Map<String, Object>> actions = actions(room);
      assertThat(actions).as(seatStatus).extracting(a -> a.get("type")).containsExactly("MOVE_OUT");
      assertThat(actions.getFirst().get("actor_side")).isEqualTo("LANDLORD");
      assertThat(actions.getFirst().get("solo_reason")).isEqualTo("NO_COUNTERPART");
      assertThat(jdbc.queryForObject(countEndRequests(), Integer.class, room)).isZero();
    }
  }

  @Test
  void 세입자_자리가_연결_전_거절_탈퇴이면_D_확인_전이라_INVALID_STATE다() throws Exception {
    // 03 A "이사함 기록"(연결 전이면 바로 거주 종료)과 SoloRule이 달라 막아 둔다(#415)
    for (String seatStatus : new String[] {"INVITED", "REJECTED", "WITHDRAWN"}) {
      UUID room = room("LIVING");
      seat(room, "TENANT", null, seatStatus);
      seat(room, "LANDLORD", landlord, "CONNECTED");

      moveOut(room, landlord, key())
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

      assertThat(actions(room)).as(seatStatus).isEmpty();
      assertThat(roomRow(room).get("status")).isEqualTo("LIVING");
      assertThat(jdbc.queryForObject(countEndRequests(), Integer.class, room)).isZero();
    }
  }

  // ---- 세입자 측 답 ----

  @Test
  void 세입자가_맞아요면_거주_종료이고_요청은_답함이다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    UUID defect = request(room, "DEFECT_CONFIRM", repair(room), "WAITING");
    moveOut(room, landlord, key()).andExpect(status().isOk());

    confirm(room, tenant, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.endRequested").value(false));

    assertThat(endRequest(room).get("status")).isEqualTo("ANSWERED");
    assertThat(requestRow(defect).get("closed_reason")).isEqualTo("ROOM_ENDED");
    assertThat(actions(room))
        .extracting(a -> a.get("type"))
        .containsExactly("REQUEST_END", "CONFIRM_END");
    assertThat(roomRow(room).get("ended_at")).isNotNull();
  }

  @Test
  void ROOM_9b_세입자가_아직_살고_있어요면_거주_중이고_요청은_닫힌다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    UUID landlordSeat = seat(room, "LANDLORD", landlord, "CONNECTED");
    UUID defect = request(room, "DEFECT_CONFIRM", repair(room), "WAITING");
    UUID link = inviteLink(room, landlordSeat);
    moveOut(room, landlord, key()).andExpect(status().isOk());

    reject(room, tenant, key(), "아직 이사 전이에요")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVING"))
        .andExpect(jsonPath("$.endRequested").value(false));

    assertThat(roomRow(room).get("status")).isEqualTo("LIVING");
    assertThat(roomRow(room).get("ended_at")).isNull();
    assertThat(endRequest(room).get("status")).isEqualTo("ANSWERED");
    assertThat(requestRow(defect).get("status")).isEqualTo("WAITING");
    assertThat(linkReason(link)).isNull();
    List<Map<String, Object>> actions = actions(room);
    assertThat(actions.getLast().get("type")).isEqualTo("REJECT_END");
    assertThat(actions.getLast().get("reason")).isEqualTo("아직 이사 전이에요");

    // 답한 뒤에는 집주인이 다시 요청할 수 있다
    moveOut(room, landlord, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endRequested").value(true));
  }

  @Test
  void 아직_살고_있어요에_사유가_없으면_REASON_REQUIRED이고_남지_않는다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    moveOut(room, landlord, key()).andExpect(status().isOk());

    reject(room, tenant, key(), " ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.REASON_REQUIRED.name()));
    mvc.perform(
            post("/api/rooms/{id}/move-out/reject", room)
                .with(loginAs(tenant))
                .header("Idempotency-Key", key()))
        .andExpect(jsonPath("$.code").value(ErrorCode.REASON_REQUIRED.name()));

    assertThat(actions(room)).hasSize(1);
    assertThat(endRequest(room).get("status")).isEqualTo("WAITING");
  }

  @Test
  void 열린_요청이_없거나_집주인_측이면_답할_수_없다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    seat(room, "LANDLORD", landlord, "CONNECTED");

    confirm(room, tenant, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    reject(room, tenant, key(), "사유")
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    moveOut(room, landlord, key()).andExpect(status().isOk());
    confirm(room, landlord, key())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertThat(actions(room)).extracting(a -> a.get("type")).containsExactly("REQUEST_END");
  }

  @Test
  void ROOM_9d_공유를_그만둔_세입자도_다시_공유하지_않고_답한다() throws Exception {
    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "SHARING_STOPPED");
    seat(room, "LANDLORD", landlord, "CONNECTED");
    moveOut(room, landlord, key()).andExpect(status().isOk());

    confirm(room, tenant, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"));
  }

  // ---- 호실 단위 ----

  @Test
  void 정산_완료_호실은_SETTLED이고_남의_호실은_NOT_FOUND다() throws Exception {
    UUID settled = room("SETTLED");
    seat(settled, "TENANT", tenant, "CONNECTED");
    moveOut(settled, tenant, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.SETTLED.name()));

    UUID room = room("LIVING");
    seat(room, "TENANT", tenant, "CONNECTED");
    moveOut(room, member(), key())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    assertThat(actions(room)).isEmpty();
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    for (String path : List.of("move-out", "move-out/confirm", "move-out/reject")) {
      mvc.perform(
              post("/api/rooms/{id}/" + path, UUID.randomUUID()).header("Idempotency-Key", key()))
          .andExpect(status().isUnauthorized());
    }
  }

  // ---- 도우미 ----

  private ResultActions moveOut(UUID room, UUID memberId, String key) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/move-out", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key));
  }

  private ResultActions confirm(UUID room, UUID memberId, String key) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/move-out/confirm", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key));
  }

  private ResultActions reject(UUID room, UUID memberId, String key, String reason)
      throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/move-out/reject", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"));
  }

  private List<Map<String, Object>> actions(UUID room) {
    return jdbc.queryForList(
        "SELECT type, actor_side, reason, created_at, payload->>'solo_reason' AS solo_reason"
            + " FROM record.action WHERE room_record_id = ? ORDER BY room_seq",
        room);
  }

  private Map<String, Object> roomRow(UUID room) {
    return jdbc.queryForMap("SELECT * FROM state.room_record WHERE id = ?", room);
  }

  private Map<String, Object> requestRow(UUID id) {
    return jdbc.queryForMap("SELECT * FROM state.response_request WHERE id = ?", id);
  }

  private Map<String, Object> endRequest(UUID room) {
    return jdbc.queryForMap(
        "SELECT * FROM state.response_request WHERE room_record_id = ? AND kind = 'END_REQUEST'"
            + " ORDER BY created_at DESC LIMIT 1",
        room);
  }

  private static String countEndRequests() {
    return "SELECT count(*) FROM state.response_request WHERE room_record_id = ?"
        + " AND kind = 'END_REQUEST'";
  }

  private String linkReason(UUID link) {
    return jdbc.queryForObject(
        "SELECT invalidated_reason FROM state.link WHERE id = ?", String.class, link);
  }

  private UUID room(String roomStatus) {
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

  private UUID seat(UUID room, String side, UUID memberId, String seatStatus) {
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

  private UUID inviteLink(UUID room, UUID party) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,"
            + " created_at, version) VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        id,
        room,
        party,
        tenant,
        LinkToken.hash(LinkToken.newToken()));
    return id;
  }

  /** 수리 건 하나. 지금 판은 지연 FK라 머리와 판을 한 트랜잭션에 넣는다. */
  private UUID repair(UUID room) {
    UUID id = UUID.randomUUID();
    UUID content = UUID.randomUUID();
    UUID part = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          jdbc.update(
              "INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order,"
                  + " created_at, version) VALUES (?, ?, '방', '천장', 0, CURRENT_TIMESTAMP, 0)",
              part,
              room);
          jdbc.update(
              """
              INSERT INTO state.repair_case (id, room_record_id, current_content_id, status,
                defect_ack, money_status, created_by_side, created_at, version)
              VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
              """,
              id,
              room,
              content);
          jdbc.update(
              """
              INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no,
                room_part_id, title, created_by_side, created_at)
              VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
              """,
              content,
              room,
              id,
              part);
        });
    return id;
  }

  private UUID request(UUID room, String kind, UUID repair, String requestStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.response_request (id, room_record_id, kind, repair_case_id,
          requested_by_side, status, created_at, version)
        VALUES (?, ?, ?, ?, 'TENANT', ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        kind,
        repair,
        requestStatus);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, status, created_at, version)
        VALUES (?, '김하늘', 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
