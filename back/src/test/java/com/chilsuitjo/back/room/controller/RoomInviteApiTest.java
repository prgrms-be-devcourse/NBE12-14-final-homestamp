package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.notification.service.PhoneHmacs;
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
 * 06 4-3 {@code POST /api/rooms/{id}/invite}(ROOM-4a·4c, CONS-2b). 보안 설정·pipeline·실제 초대 링크
 * 발급({@code InviteLinkService})·아웃박스까지 띄워 부른다. 링크는 pipeline {@code save} 안에서만 만들 수 있어서 실제 구현으로
 * 확인한다(#236).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomInviteApiTest {

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhoneHmacs phoneHmacs;

  private UUID me;
  private String myPhone;
  private String phone;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    myPhone = newPhone();
    me = member(myPhone);
    phone = newPhone();
  }

  @Test
  void 동의하고_초대하면_동의_초대_자리_링크_문자가_함께_남는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(room, me, key(), body(dashed(phone), "v1", null))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.partyId").value(counterpart.toString()))
        .andExpect(jsonPath("$.status").value("INVITED"));

    List<Map<String, Object>> actions = actions(room);
    assertThat(actions)
        .extracting(row -> row.get("type"))
        .containsExactly("SHARE_CONSENT", "INVITE");
    assertThat(actions.get(0).get("consent_version")).isEqualTo("v1");
    assertThat(actions.get(1).get("party_id")).isEqualTo(counterpart);
    // 번호는 행동 기록에 남기지 않는다(05 action.payload "이름·번호 금지")
    assertThat(actions)
        .allSatisfy(row -> assertThat(String.valueOf(row.get("payload"))).doesNotContain(phone));

    Map<String, Object> party =
        jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", counterpart);
    assertThat(party.get("status")).isEqualTo("INVITED");
    assertThat(party.get("invite_phone")).isEqualTo(phone);

    Map<String, Object> link =
        jdbc.queryForMap("SELECT * FROM state.link WHERE room_record_id = ?", room);
    assertThat(link.get("kind")).isEqualTo("INVITE");
    assertThat(link.get("party_id")).isEqualTo(counterpart);
    assertThat(link.get("inspection_id")).isNull();
    assertThat(link.get("issued_by_member_id")).isEqualTo(me);

    Map<String, Object> notice =
        jdbc.queryForMap("SELECT * FROM state.notice WHERE room_record_id = ?", room);
    assertThat(notice.get("kind")).isEqualTo("INVITE");
    assertThat(notice.get("channel")).isEqualTo("SMS");
    assertThat(notice.get("link_id")).isEqualTo(link.get("id"));
    assertThat(notice.get("recipient_party_id")).isEqualTo(counterpart);
    assertThat(outboxCount(notice.get("id"))).isEqualTo(1);
  }

  @Test
  void 번호만_있는_자리도_초대한다() throws Exception {
    UUID room = room("LIVING", "LANDLORD");
    seat(room, "LANDLORD", me, "CONNECTED", null);
    UUID counterpart = seat(room, "TENANT", null, "NUMBER_ONLY", newPhone());

    invite(room, me, key(), body(phone, "v1", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partyId").value(counterpart.toString()));

    assertThat(
            jdbc.queryForObject(
                "SELECT invite_phone FROM state.party WHERE id = ?", String.class, counterpart))
        .isEqualTo(phone);
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려주고_더_남기지_않는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);
    String key = key();
    invite(room, me, key, body(phone, "v1", null)).andExpect(status().isOk());

    invite(room, me, key, body(phone, "v1", null))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.partyId").value(counterpart.toString()))
        .andExpect(jsonPath("$.status").value("INVITED"));

    assertThat(actions(room)).hasSize(2);
    assertThat(count("state.link", room)).isEqualTo(1);
    assertThat(count("state.notice", room)).isEqualTo(1);
  }

  @Test
  void 점검_기록_링크면_그_점검으로_링크를_만든다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    seat(room, "LANDLORD", null, "EMPTY", null);
    UUID inspection = inspection(room);

    invite(
            room,
            me,
            key(),
            body(phone, "v1", Map.of("kind", "INSPECTION", "id", inspection.toString())))
        .andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject(
                "SELECT inspection_id FROM state.link WHERE room_record_id = ?", UUID.class, room))
        .isEqualTo(inspection);
    // 확인 요청 열기는 D의 메서드가 생긴 뒤 더한다(#163). 지금은 요청을 만들지 않는다
    assertThat(count("state.response_request", room)).isZero();
  }

  @Test
  void 다른_호실의_점검이면_NOT_FOUND이고_아무것도_남지_않는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);
    UUID other = room("LIVING", "TENANT");
    UUID otherInspection = inspection(other);

    invite(
            room,
            me,
            key(),
            body(phone, "v1", Map.of("kind", "INSPECTION", "id", otherInspection.toString())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 점검이_아닌_기록이면_VALIDATION_FAILED다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(
            room,
            me,
            key(),
            body(phone, "v1", Map.of("kind", "REPAIR_CASE", "id", UUID.randomUUID().toString())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 내_인증_번호면_SELF_INVITE이고_아무것도_남지_않는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(room, me, key(), body(dashed(myPhone), "v1", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.SELF_INVITE.name()));

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 수신_거부한_번호면_PHONE_OPTED_OUT이고_아무것도_남지_않는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "NUMBER_ONLY", phone);
    // 실제 SmsOptOuts(JpaSmsOptOuts)가 읽는 행. 형식만 다른 번호(하이픈)로 보내도 같은 번호로 본다
    String hmac = phoneHmacs.phoneHmac(phone);
    jdbc.update("INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES (?, now())", hmac);
    try {
      invite(room, me, key(), body(dashed(phone), "v1", null))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.PHONE_OPTED_OUT.name()));

      assertNothingSaved(room, counterpart);
      assertThat(
              jdbc.queryForObject(
                  "SELECT invite_phone FROM state.party WHERE id = ?", String.class, counterpart))
          .isEqualTo(phone);
    } finally {
      jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = ?", hmac);
    }
  }

  @Test
  void 동의_문구_판이_없으면_CONSENT_REQUIRED다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(room, me, key(), body(phone, " ", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUIRED.name()));

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 휴대폰_번호_모양이_아니면_VALIDATION_FAILED다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(room, me, key(), body("02-123-4567", "v1", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 호실을_만들지_않은_측이면_INVALID_STATE다() throws Exception {
    UUID creator = member(newPhone());
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", creator, "CONNECTED", null);
    UUID mine = seat(room, "LANDLORD", me, "CONNECTED", null);

    invite(room, me, key(), body(phone, "v1", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    assertNothingSaved(room, mine);
  }

  @Test
  void 이미_초대했거나_연결됐으면_INVALID_STATE다() throws Exception {
    for (String seatStatus : new String[] {"INVITED", "CONNECTED", "REJECTED"}) {
      UUID room = room("LIVING", "TENANT");
      seat(room, "TENANT", me, "CONNECTED", null);
      UUID counterpart =
          seat(
              room,
              "LANDLORD",
              seatStatus.equals("CONNECTED") ? member(newPhone()) : null,
              seatStatus,
              null);

      invite(room, me, key(), body(phone, "v1", null))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

      assertThat(actions(room)).as(seatStatus).isEmpty();
      assertThat(count("state.link", room)).as(seatStatus).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM state.party WHERE id = ?", String.class, counterpart))
          .isEqualTo(seatStatus);
    }
  }

  @Test
  void 거주_종료_뒤에는_INVALID_STATE_정산_완료_뒤에는_SETTLED다() throws Exception {
    UUID ended = room("ENDED", "TENANT");
    seat(ended, "TENANT", me, "CONNECTED", null);
    UUID endedSeat = seat(ended, "LANDLORD", null, "EMPTY", null);
    UUID settled = room("SETTLED", "TENANT");
    seat(settled, "TENANT", me, "CONNECTED", null);
    UUID settledSeat = seat(settled, "LANDLORD", null, "EMPTY", null);

    invite(ended, me, key(), body(phone, "v1", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    invite(settled, me, key(), body(phone, "v1", null))
        .andExpect(jsonPath("$.code").value(ErrorCode.SETTLED.name()));

    assertNothingSaved(ended, endedSeat);
    assertNothingSaved(settled, settledSeat);
  }

  @Test
  void 당사자가_아니면_NOT_FOUND다() throws Exception {
    UUID owner = member(newPhone());
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", owner, "CONNECTED", null);
    UUID counterpart = seat(room, "LANDLORD", null, "EMPTY", null);

    invite(room, me, key(), body(phone, "v1", null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    invite(UUID.randomUUID(), me, key(), body(phone, "v1", null)).andExpect(status().isNotFound());

    assertNothingSaved(room, counterpart);
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/invite", UUID.randomUUID())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(phone, "v1", null)))
        .andExpect(status().isUnauthorized());
  }

  private ResultActions invite(UUID room, UUID memberId, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/invite", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private void assertNothingSaved(UUID room, UUID party) {
    assertThat(actions(room)).isEmpty();
    assertThat(count("state.link", room)).isZero();
    assertThat(count("state.notice", room)).isZero();
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", party);
    assertThat(row.get("status")).isNotEqualTo("INVITED");
  }

  private List<Map<String, Object>> actions(UUID room) {
    return jdbc.queryForList(
        "SELECT type, party_id, payload, payload->>'consentVersion' AS consent_version"
            + " FROM record.action WHERE room_record_id = ? ORDER BY room_seq",
        room);
  }

  private int count(String table, UUID room) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE room_record_id = ?", Integer.class, room);
  }

  private int outboxCount(Object noticeId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.outbox WHERE dedupe_key = ?", Integer.class, "sms:" + noticeId);
  }

  private static String body(String phone, String consentVersion, Map<String, String> record) {
    String recordJson =
        record == null
            ? "null"
            : "{\"kind\":\"" + record.get("kind") + "\",\"id\":\"" + record.get("id") + "\"}";
    return "{\"phone\":\""
        + phone
        + "\",\"consentVersion\":\""
        + consentVersion
        + "\",\"record\":"
        + recordJson
        + "}";
  }

  private UUID room(String roomStatus, String createdBySide) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, ended_at, settled_at, created_at, version)
        VALUES (?, ?, '302호', ?, ?,
          CASE WHEN CAST(? AS varchar) = 'LIVING' THEN NULL ELSE CURRENT_TIMESTAMP END,
          CASE WHEN CAST(? AS varchar) = 'SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        roomStatus,
        createdBySide,
        roomStatus,
        roomStatus);
    return id;
  }

  private UUID seat(UUID room, String side, UUID memberId, String seatStatus, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?,
          CASE WHEN CAST(? AS varchar) = 'CONNECTED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        invitePhone,
        seatStatus,
        seatStatus);
    return id;
  }

  private UUID inspection(UUID room) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)"
            + " VALUES (?, ?, 'MOVE_IN', 'TENANT', 'REQUESTED', CURRENT_TIMESTAMP, 0)",
        id,
        room);
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

  /** 하이픈을 넣은 모양(010-1234-5678). 서버는 숫자만 남겨 저장한다. */
  private static String dashed(String digits) {
    return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
  }

  /** 다른 테스트와 겹치지 않는 휴대폰 번호(숫자만). */
  private static String newPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
