package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.rule.LinkToken;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * 06 4-3 {@code POST /api/invitations/{partyId}/connect}·{@code reject}(ROOM-5a·5b, CONS-2b). 아직
 * 당사자가 아닌 회원이라 pipeline이 초대 자리로 잠근다({@code asInvitee}, #251). 보안 설정·pipeline·실제 링크 무효화({@code
 * InviteLinkService})까지 띄워 부른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationAnswerApiTest {

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private String phone;
  private UUID me;
  private UUID inviter;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    phone = newPhone();
    me = member(phone);
    inviter = member(newPhone());
  }

  @Test
  void 같은_번호로_인증한_회원이_동의하고_연결하면_연결됨이고_링크가_닫힌다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    UUID link = inviteLink(room, seat);

    connect(seat, me, key(), "v1")
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()))
        .andExpect(jsonPath("$.partyId").value(seat.toString()))
        .andExpect(jsonPath("$.status").value("CONNECTED"));

    Map<String, Object> party = jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", seat);
    assertThat(party.get("status")).isEqualTo("CONNECTED");
    assertThat(party.get("member_id")).isEqualTo(me);
    assertThat(party.get("invite_phone")).isNull();
    assertThat(party.get("connected_at")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT counterpart_first_connected_at FROM state.room_record WHERE id = ?",
                Object.class,
                room))
        .isEqualTo(party.get("connected_at"));
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_reason FROM state.link WHERE id = ?", String.class, link))
        .isEqualTo("CONNECTED");

    List<Map<String, Object>> actions = actions(room);
    assertThat(actions)
        .extracting(row -> row.get("type"))
        .containsExactly("CONNECT", "SHARE_CONSENT");
    assertThat(actions).allSatisfy(row -> assertThat(row.get("actor_side")).isEqualTo("LANDLORD"));
    assertThat(actions.get(0).get("party_id")).isEqualTo(seat);
    assertThat(actions.get(1).get("consent_version")).isEqualTo("v1");
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려주고_더_남기지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    String key = key();
    connect(seat, me, key, "v1").andExpect(status().isOk());

    connect(seat, me, key, "v1")
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()))
        .andExpect(jsonPath("$.status").value("CONNECTED"));

    assertThat(actions(room)).hasSize(2);
  }

  @Test
  void 이미_연결했으면_다른_키로는_INVALID_STATE다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    connect(seat, me, key(), "v1").andExpect(status().isOk());

    connect(seat, me, key(), "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
  }

  @Test
  void ROOM_5b_인증_번호가_초대_번호와_다르면_PHONE_MISMATCH이고_아무것도_남지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, newPhone());

    connect(seat, me, key(), "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.PHONE_MISMATCH.name()));

    assertNotAnswered(room, seat);
  }

  @Test
  void 인증_번호가_없으면_PHONE_MISMATCH다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    jdbc.update("UPDATE state.member SET phone = NULL, phone_verified_at = NULL WHERE id = ?", me);

    connect(seat, me, key(), "v1")
        .andExpect(jsonPath("$.code").value(ErrorCode.PHONE_MISMATCH.name()));

    assertNotAnswered(room, seat);
  }

  @Test
  void CONS_2b_동의_문구_판_없이는_연결되지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);

    connect(seat, me, key(), " ")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUIRED.name()));
    mvc.perform(
            post("/api/invitations/{id}/connect", seat)
                .with(loginAs(me))
                .header("Idempotency-Key", key()))
        .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUIRED.name()));

    assertNotAnswered(room, seat);
  }

  @Test
  void 초대됨이_아닌_자리는_연결도_거절도_INVALID_STATE다() throws Exception {
    for (String seatStatus : new String[] {"EMPTY", "NUMBER_ONLY", "REJECTED"}) {
      UUID room = room("LIVING");
      UUID seat = seat(room, "LANDLORD", null, seatStatus, phone);

      connect(seat, me, key(), "v1")
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
      reject(seat, me, key()).andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

      assertThat(actions(room)).as(seatStatus).isEmpty();
    }
  }

  @Test
  void 거주_종료_전에_받은_초대는_정산_완료_전까지_연결할_수_있다() throws Exception {
    // 임시 정책(#163 3-1, 팀 최종 합의 전): 받은 초대 목록과 같은 기준
    UUID ended = room("ENDED");
    UUID endedSeat = invitedSeat(ended, phone);
    UUID settled = room("SETTLED");
    UUID settledSeat = invitedSeat(settled, phone);

    connect(endedSeat, me, key(), "v1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONNECTED"));
    connect(settledSeat, me, key(), "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.SETTLED.name()));

    assertNotAnswered(settled, settledSeat);
  }

  @Test
  void 합류_전_기록의_초대는_JOINED_RECORD다() throws Exception {
    UUID target = room("LIVING");
    UUID joined = room("LIVING");
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        target,
        joined);
    UUID seat = invitedSeat(joined, phone);

    connect(seat, me, key(), "v1")
        .andExpect(jsonPath("$.code").value(ErrorCode.JOINED_RECORD.name()));
    reject(seat, me, key()).andExpect(jsonPath("$.code").value(ErrorCode.JOINED_RECORD.name()));

    assertNotAnswered(joined, seat);
  }

  @Test
  void 없는_자리면_NOT_FOUND다() throws Exception {
    connect(UUID.randomUUID(), me, key(), "v1")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    reject(UUID.randomUUID(), me, key()).andExpect(status().isNotFound());
  }

  @Test
  void 제_집이_아니에요를_누르면_거절됨이고_번호와_링크는_그대로다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    UUID link = inviteLink(room, seat);

    reject(seat, me, key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partyId").value(seat.toString()))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        // 당사자가 아니라 호실 기록 ID는 주지 않는다
        .andExpect(jsonPath("$.roomRecordId").doesNotExist());

    Map<String, Object> party = jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", seat);
    assertThat(party.get("status")).isEqualTo("REJECTED");
    assertThat(party.get("member_id")).isNull();
    // 번호를 고쳐 다시 초대할 수 있는 자리라 초대 번호는 남긴다(03 B)
    assertThat(party.get("invite_phone")).isEqualTo(phone);
    // 거절할 때 링크를 무효로 할지는 D 확인 전이라 그대로 둔다(#163)
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, link))
        .isNull();
    assertThat(actions(room)).extracting(row -> row.get("type")).containsExactly("REJECT_INVITE");
  }

  @Test
  void 번호가_다르면_거절할_수_없다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, newPhone());

    reject(seat, me, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.PHONE_MISMATCH.name()));

    assertNotAnswered(room, seat);
  }

  @Test
  void 이_호실의_다른_자리에_이미_있는_회원은_연결할_수_없다() throws Exception {
    // 한 호실에 한 회원은 한 자리(불변식 13). 초대한 사람이 나중에 번호를 바꿔 초대 번호와 같아진 경우 등
    UUID room = room("LIVING");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID seat = seat(room, "LANDLORD", null, "INVITED", phone);

    connect(seat, me, key(), "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertThat(actions(room)).isEmpty();
    assertThat(
            jdbc.queryForObject("SELECT status FROM state.party WHERE id = ?", String.class, seat))
        .isEqualTo("INVITED");
  }

  @Test
  void 연결된_자리면_번호가_달라도_자리_상태가_먼저다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    connect(seat, me, key(), "v1").andExpect(status().isOk());
    UUID stranger = member(newPhone());

    connect(seat, stranger, key(), "v1")
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
  }

  @Test
  void 거절도_같은_키로_다시_오면_처음_결과를_돌려준다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    String key = key();
    reject(seat, me, key).andExpect(status().isOk());

    reject(seat, me, key)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(actions(room)).hasSize(1);
  }

  @Test
  void 연결하면_그_자리의_초대_링크만_닫힌다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    UUID mine = inviteLink(room, seat);
    UUID otherRoom = room("LIVING");
    UUID otherSeat = invitedSeat(otherRoom, newPhone());
    UUID other = inviteLink(otherRoom, otherSeat);

    connect(seat, me, key(), "v1").andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, mine))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, other))
        .isNull();
  }

  @Test
  void 이_호실의_다른_자리에_이미_있는_회원은_거절할_수도_없다() throws Exception {
    // 호실을 만든 사람의 인증 번호가 나중에 초대 번호와 같아져도 상대 자리를 거절하지 못한다(#265 리뷰)
    UUID room = room("LIVING");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID seat = seat(room, "LANDLORD", null, "INVITED", phone);

    reject(seat, me, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertThat(actions(room)).isEmpty();
    assertThat(
            jdbc.queryForObject("SELECT status FROM state.party WHERE id = ?", String.class, seat))
        .isEqualTo("INVITED");
  }

  @Test
  void 같은_키로_다시_온_거절에는_그_뒤_다른_회원이_연결한_호실_ID를_주지_않는다() throws Exception {
    // 거절 → 번호를 고쳐 다시 초대 → 다른 회원이 연결 → 처음 거절한 회원이 같은 키로 다시 보냄(#265 리뷰)
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    String rejectKey = key();
    reject(seat, me, rejectKey).andExpect(status().isOk());

    String otherPhone = newPhone();
    UUID other = member(otherPhone);
    jdbc.update(
        "UPDATE state.party SET status = 'INVITED', invite_phone = ? WHERE id = ?",
        otherPhone,
        seat);
    connect(seat, other, key(), "v1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomRecordId").value(room.toString()));

    reject(seat, me, rejectKey)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("CONNECTED"))
        .andExpect(jsonPath("$.roomRecordId").doesNotExist());
  }

  @Test
  void 초대_회차_ID가_없으면_연결도_거절도_VALIDATION_FAILED다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);

    connect(seat, null, me, key(), "v1")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    mvc.perform(
            post("/api/invitations/{id}/reject", seat)
                .with(loginAs(me))
                .header("Idempotency-Key", key()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

    assertNotAnswered(room, seat);
  }

  @Test
  void X에서_Y를_거쳐_다시_X로_초대하면_예전_초대_화면의_늦은_연결과_거절은_INVALID_STATE다() throws Exception {
    // #437·#370 ①: 자리 ID와 번호가 같아도 초대 회차가 다르면 예전 요청이다
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    UUID first = inviteIdOf(seat);
    jdbc.update(
        "UPDATE state.party SET invite_phone = ?, invite_id = gen_random_uuid() WHERE id = ?",
        newPhone(),
        seat);
    jdbc.update(
        "UPDATE state.party SET invite_phone = ?, invite_id = gen_random_uuid() WHERE id = ?",
        phone,
        seat);

    reject(seat, first, me, key())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    connect(seat, first, me, key(), "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    assertNotAnswered(room, seat);

    // 지금 회차로 보내면 연결된다
    connect(seat, me, key(), "v1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONNECTED"));
  }

  @Test
  void 회차_없이_초대된_자리는_자리_ID를_회차로_받아_연결한다() throws Exception {
    // V14 배포 중 이전 버전 앱이 회차 없이 초대한 자리(04 8장 "이전 버전 앱과도 맞게")
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room, phone);
    jdbc.update("UPDATE state.party SET invite_id = NULL WHERE id = ?", seat);

    mvc.perform(get("/api/invitations").with(loginAs(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].partyId").value(seat.toString()))
        .andExpect(jsonPath("$[0].inviteId").value(seat.toString()));
    reject(seat, UUID.randomUUID(), me, key())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    connect(seat, seat, me, key(), "v1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONNECTED"));
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    mvc.perform(
            post("/api/invitations/{id}/connect", UUID.randomUUID())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consentVersion\":\"v1\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/invitations/{id}/reject", UUID.randomUUID())
                .header("Idempotency-Key", key()))
        .andExpect(status().isUnauthorized());
  }

  /** 지금 초대 회차로 연결한다(받은 초대 목록이 준 값). */
  private ResultActions connect(UUID party, UUID memberId, String key, String consentVersion)
      throws Exception {
    return connect(party, inviteIdOf(party), memberId, key, consentVersion);
  }

  private ResultActions connect(
      UUID party, UUID inviteId, UUID memberId, String key, String consentVersion)
      throws Exception {
    return mvc.perform(
        post("/api/invitations/{id}/connect", party)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"consentVersion\":\""
                    + consentVersion
                    + "\",\"inviteId\":"
                    + json(inviteId)
                    + "}"));
  }

  /** 지금 초대 회차로 거절한다(받은 초대 목록이 준 값). */
  private ResultActions reject(UUID party, UUID memberId, String key) throws Exception {
    return reject(party, inviteIdOf(party), memberId, key);
  }

  private ResultActions reject(UUID party, UUID inviteId, UUID memberId, String key)
      throws Exception {
    return mvc.perform(
        post("/api/invitations/{id}/reject", party)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"inviteId\":" + json(inviteId) + "}"));
  }

  /** 자리의 지금 초대 회차. 없는 자리면 아무 값(서버가 자리부터 찾아 NOT_FOUND). */
  private UUID inviteIdOf(UUID party) {
    return jdbc
        .queryForList("SELECT invite_id FROM state.party WHERE id = ?", UUID.class, party)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseGet(UUID::randomUUID);
  }

  private static String json(UUID value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private void assertNotAnswered(UUID room, UUID seat) {
    assertThat(actions(room)).isEmpty();
    Map<String, Object> party = jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", seat);
    assertThat(party.get("status")).isEqualTo("INVITED");
    assertThat(party.get("member_id")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT counterpart_first_connected_at FROM state.room_record WHERE id = ?",
                Object.class,
                room))
        .isNull();
  }

  private List<Map<String, Object>> actions(UUID room) {
    return jdbc.queryForList(
        "SELECT type, party_id, actor_side, payload->>'consentVersion' AS consent_version"
            + " FROM record.action WHERE room_record_id = ? ORDER BY room_seq",
        room);
  }

  /** 초대한 사람이 세입자로 만든 호실의 집주인 자리, 이 번호로 초대됨. */
  private UUID invitedSeat(UUID room, String invitePhone) {
    seat(room, "TENANT", inviter, "CONNECTED", null);
    return seat(room, "LANDLORD", null, "INVITED", invitePhone);
  }

  private UUID inviteLink(UUID room, UUID party) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,"
            + " created_at, version) VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        id,
        room,
        party,
        inviter,
        LinkToken.hash(LinkToken.newToken()));
    return id;
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

  private UUID seat(UUID room, String side, UUID memberId, String seatStatus, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, invite_id, connected_at,
           created_at, version)
        VALUES (?, ?, ?, ?, ?, ?,
          CASE WHEN CAST(? AS varchar) IN ('INVITED', 'REJECTED') THEN gen_random_uuid() END,
          CASE WHEN CAST(? AS varchar) = 'CONNECTED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        invitePhone,
        seatStatus,
        seatStatus,
        seatStatus);
    return id;
  }

  private UUID member(String verifiedPhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, phone, phone_verified_at, status, created_at, version)
        VALUES (?, '김하늘', ?, CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        verifiedPhone);
    return id;
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  /** 다른 테스트와 겹치지 않는 휴대폰 번호(숫자만). */
  private static String newPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
