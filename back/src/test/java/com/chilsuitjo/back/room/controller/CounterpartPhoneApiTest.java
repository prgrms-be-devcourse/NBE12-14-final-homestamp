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
import com.chilsuitjo.back.notification.service.PhoneHmacs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
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
 * 06 4-3 {@code POST /api/rooms/{id}/counterpart-phone}(ROOM-4·LINK-4, #437). 처음 초대는 실제 {@code
 * invite} API로 하고, 번호를 고쳐 다시 초대할 때 실제 링크 무효({@code InviteLinkService})·보내지 않은 문자 취소({@code
 * SmsCancellationService})·새 링크와 초대 문자가 한 트랜잭션에 들어가는지 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.notification.wait-service=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CounterpartPhoneApiTest {

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  /** 지금 판({@code terms.version.share-consent} = {@code v1})이 아닌 판. 배포 전 화면이 보낸 옛 판과 같다. */
  private static final String STALE_VERSION = "v0";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhoneHmacs phoneHmacs;

  private UUID me;
  private String myPhone;
  private String oldPhone;
  private String newPhone;
  private final List<UUID> rooms = new ArrayList<>();

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    myPhone = phone();
    me = member(myPhone);
    oldPhone = phone();
    newPhone = phone();
  }

  /**
   * 이 테스트가 커밋한 문자를 치운다. 보낼 줄은 같은 DB를 쓰는 작업자 테스트가 가져가지 않게 닫고, 취소된 통지·문자 줄은 V10 이전 CHECK를 되살려 보는
   * 마이그레이션 테스트({@code NoticeOutboxCancelledMigrationTest})가 깨지지 않게 지운다.
   */
  @AfterEach
  void cleanUp() {
    for (UUID r : rooms) {
      jdbc.update(
          """
          DELETE FROM state.outbox
          WHERE dedupe_key IN (SELECT 'sms:' || id FROM state.notice WHERE room_record_id = ?)
          """,
          r);
      jdbc.update("DELETE FROM state.notice WHERE room_record_id = ? AND status = 'CANCELLED'", r);
    }
  }

  @Test
  void 초대됨_자리의_번호를_고치면_옛_링크와_보내지_않은_문자가_닫히고_새_번호로_다시_초대된다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    Map<String, Object> before = seatRow(seat);
    UUID oldLink = onlyInviteLink(room);
    UUID oldNotice = noticeOf(oldLink);

    change(room, me, key(), body(dashed(newPhone), "v1"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.partyId").value(seat.toString()))
        .andExpect(jsonPath("$.status").value("INVITED"));

    Map<String, Object> after = seatRow(seat);
    assertThat(after.get("status")).isEqualTo("INVITED");
    assertThat(after.get("invite_phone")).isEqualTo(newPhone);
    assertThat(after.get("invite_id")).isNotNull().isNotEqualTo(before.get("invite_id"));

    Map<String, Object> old = jdbc.queryForMap("SELECT * FROM state.link WHERE id = ?", oldLink);
    assertThat(old.get("invalidated_reason")).isEqualTo("REINVITED");
    assertThat(statusOf("state.notice", oldNotice)).isEqualTo("CANCELLED");
    assertThat(outboxStatus(oldNotice)).isEqualTo("CANCELLED");

    List<Map<String, Object>> valid =
        jdbc.queryForList(
            "SELECT id FROM state.link WHERE room_record_id = ? AND kind = 'INVITE'"
                + " AND invalidated_at IS NULL",
            room);
    assertThat(valid).hasSize(1);
    UUID newLink = (UUID) valid.get(0).get("id");
    UUID newNotice = noticeOf(newLink);
    assertThat(statusOf("state.notice", newNotice)).isEqualTo("REQUESTED");
    assertThat(outboxStatus(newNotice)).isEqualTo("PENDING");

    List<Map<String, Object>> actions = actions(room);
    assertThat(actions)
        .extracting(row -> row.get("type"))
        .containsExactly("SHARE_CONSENT", "INVITE", "SHARE_CONSENT", "CHANGE_INVITE_PHONE");
    assertThat(actions.get(3).get("party_id")).isEqualTo(seat);
    assertThat(actions.get(2).get("consent_version")).isEqualTo("v1");
    // 번호는 행동 기록에 남기지 않는다(05 action.payload "이름·번호 금지")
    assertThat(actions)
        .allSatisfy(row ->
            assertThat(String.valueOf(row.get("payload")))
                .doesNotContain(oldPhone)
                .doesNotContain(newPhone));
  }

  @Test
  void 거절된_자리도_번호를_고쳐_다시_초대한다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    jdbc.update("UPDATE state.party SET status = 'REJECTED' WHERE id = ?", seat);

    change(room, me, key(), body(newPhone, "v1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INVITED"));

    assertThat(seatRow(seat).get("invite_phone")).isEqualTo(newPhone);
  }

  @Test
  void 옛_번호_주인은_초대를_더_보지_못하고_새_번호_주인은_새_회차로_본다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    UUID oldOwner = member(oldPhone);
    UUID newOwner = member(newPhone);
    String oldInviteId = String.valueOf(seatRow(seat).get("invite_id"));

    change(room, me, key(), body(newPhone, "v1")).andExpect(status().isOk());

    mvc.perform(get("/api/invitations").with(loginAs(oldOwner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/invitations").with(loginAs(newOwner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].partyId").value(seat.toString()))
        .andExpect(jsonPath("$[0].inviteId").value(String.valueOf(seatRow(seat).get("invite_id"))));

    // 옛 번호 주인의 늦은 연결·거절은 받지 않는다(예전 회차라 자리 상태 다음 INVALID_STATE)
    answer(seat, oldOwner, "reject", "{\"inviteId\":\"" + oldInviteId + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    answer(
            seat,
            oldOwner,
            "connect",
            "{\"consentVersion\":\"v1\",\"inviteId\":\"" + oldInviteId + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    assertThat(seatRow(seat).get("status")).isEqualTo("INVITED");
  }

  @Test
  void X에서_Y를_거쳐_다시_X로_초대하면_X의_예전_초대_화면_거절은_새_초대를_거절하지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    UUID x = member(oldPhone);
    String firstInviteId = String.valueOf(seatRow(seat).get("invite_id"));

    change(room, me, key(), body(newPhone, "v1")).andExpect(status().isOk());
    change(room, me, key(), body(oldPhone, "v1")).andExpect(status().isOk());

    answer(seat, x, "reject", "{\"inviteId\":\"" + firstInviteId + "\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    assertThat(seatRow(seat).get("status")).isEqualTo("INVITED");

    String current = String.valueOf(seatRow(seat).get("invite_id"));
    answer(seat, x, "connect", "{\"consentVersion\":\"v1\",\"inviteId\":\"" + current + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONNECTED"));
  }

  @Test
  void 같은_키로_다시_오면_처음_결과를_돌려주고_더_남기지_않는다() throws Exception {
    UUID room = room("LIVING");
    invitedSeat(room);
    String key = key();
    change(room, me, key, body(newPhone, "v1")).andExpect(status().isOk());

    change(room, me, key, body(newPhone, "v1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("INVITED"));

    assertThat(actions(room)).hasSize(4);
    assertThat(count("state.link", room)).isEqualTo(2);
  }

  @Test
  void 지금_초대_번호와_같으면_VALIDATION_FAILED이고_아무것도_바뀌지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);

    change(room, me, key(), body(dashed(oldPhone), "v1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

    assertUnchanged(room, seat);
  }

  @Test
  void 거절된_자리도_지금_번호와_같으면_VALIDATION_FAILED다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    jdbc.update("UPDATE state.party SET status = 'REJECTED' WHERE id = ?", seat);

    change(room, me, key(), body(oldPhone, "v1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    assertThat(actions(room)).hasSize(2);
  }

  @Test
  void 거주_종료나_만든_측이_아니면_같은_번호여도_INVALID_STATE가_먼저다() throws Exception {
    // 권한을 확인하기 전에 번호가 같은지 알리지 않는다
    UUID ended = room("LIVING");
    invitedSeat(ended);
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        ended);
    change(ended, me, key(), body(oldPhone, "v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

    UUID landlord = member(phone());
    UUID other = room("LIVING");
    seat(other, "TENANT", null, "INVITED", oldPhone);
    seat(other, "LANDLORD", landlord, "CONNECTED", null);
    change(other, landlord, key(), body(oldPhone, "v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
  }

  @Test
  void 번호가_없거나_모양이_틀리면_VALIDATION_FAILED다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);

    for (String bad : new String[] {null, " ", "12345"}) {
      change(room, me, key(), body(bad, "v1"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
    assertUnchanged(room, seat);
  }

  @Test
  void 동의_문구_판이_없으면_CONSENT_REQUIRED다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);

    change(room, me, key(), body(newPhone, " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUIRED.name()));

    assertUnchanged(room, seat);
  }

  @Test
  void 동의_문구_판이_지금_판이_아니면_VERSION_CONFLICT이고_아무것도_바뀌지_않는다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);

    change(room, me, key(), body(newPhone, STALE_VERSION))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.name()));

    assertUnchanged(room, seat);
  }

  @Test
  void 옛_판은_입력_검사와_당사자_확인_뒤_자리_규칙보다_먼저다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    // 입력 검사(번호 없음·모양)가 먼저
    change(room, me, key(), body(null, STALE_VERSION))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    change(room, me, key(), body("12345", STALE_VERSION))
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    // 당사자 확인(잠금)이 먼저
    change(room, member(phone()), key(), body(newPhone, STALE_VERSION))
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    // 자리 규칙(지금 번호와 같음·내 인증 번호)보다 먼저
    change(room, me, key(), body(oldPhone, STALE_VERSION))
        .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.name()));
    change(room, me, key(), body(myPhone, STALE_VERSION))
        .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.name()));
    assertUnchanged(room, seat);
  }

  @Test
  void 같은_키의_재생은_문구_판을_다시_보지_않고_처음_결과를_돌려준다() throws Exception {
    UUID room = room("LIVING");
    invitedSeat(room);
    String key = key();
    change(room, me, key, body(newPhone, "v1")).andExpect(status().isOk());

    // 처음 요청 뒤 판이 바뀐 것과 같다: 처음 실행 검사는 재생에서 부르지 않는다
    change(room, me, key, body(newPhone, STALE_VERSION))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("INVITED"));

    assertThat(actions(room)).hasSize(4);
    assertThat(count("state.link", room)).isEqualTo(2);
  }

  @Test
  void 내_인증_번호면_SELF_INVITE다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);

    change(room, me, key(), body(myPhone, "v1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.SELF_INVITE.name()));

    assertUnchanged(room, seat);
  }

  @Test
  void 수신_거부한_번호면_PHONE_OPTED_OUT이다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    String hmac = phoneHmacs.phoneHmac(newPhone);
    jdbc.update("INSERT INTO state.sms_opt_out (phone_hmac, created_at) VALUES (?, now())", hmac);
    try {
      change(room, me, key(), body(newPhone, "v1"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.PHONE_OPTED_OUT.name()));

      assertUnchanged(room, seat);
    } finally {
      jdbc.update("DELETE FROM state.sms_opt_out WHERE phone_hmac = ?", hmac);
    }
  }

  @Test
  void 초대됨_거절됨이_아닌_자리는_INVALID_STATE다() throws Exception {
    for (String seatStatus : new String[] {"EMPTY", "NUMBER_ONLY", "CONNECTED"}) {
      UUID room = room("LIVING");
      seat(room, "TENANT", me, "CONNECTED", null);
      UUID other = seatStatus.equals("CONNECTED") ? member(phone()) : null;
      seat(room, "LANDLORD", other, seatStatus, seatStatus.equals("NUMBER_ONLY") ? oldPhone : null);

      change(room, me, key(), body(newPhone, "v1"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));

      assertThat(actions(room)).as(seatStatus).isEmpty();
    }
  }

  @Test
  void 호실을_만들지_않은_측이면_INVALID_STATE다() throws Exception {
    // 만든 측이 세입자인 호실에서 집주인 자리에 연결된 회원(만든 측이 아님)
    UUID landlord = member(phone());
    UUID other = room("LIVING");
    seat(other, "TENANT", null, "INVITED", oldPhone);
    seat(other, "LANDLORD", landlord, "CONNECTED", null);

    change(other, landlord, key(), body(newPhone, "v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    assertThat(actions(other)).isEmpty();
  }

  @Test
  void 거주_종료_뒤에는_INVALID_STATE다() throws Exception {
    UUID room = room("LIVING");
    UUID seat = invitedSeat(room);
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    change(room, me, key(), body(newPhone, "v1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_STATE.name()));
    assertThat(seatRow(seat).get("invite_phone")).isEqualTo(oldPhone);
  }

  @Test
  void 당사자가_아니면_NOT_FOUND다() throws Exception {
    UUID room = room("LIVING");
    invitedSeat(room);

    change(room, member(phone()), key(), body(newPhone, "v1")).andExpect(status().isNotFound());
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/counterpart-phone", UUID.randomUUID())
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(newPhone, "v1")))
        .andExpect(status().isUnauthorized());
  }

  /** 세입자인 내가 만든 호실의 집주인 자리를 실제 초대 API로 옛 번호로 초대한다. 처음 초대의 동의·초대 행동 2개가 남는다. */
  private UUID invitedSeat(UUID room) throws Exception {
    seat(room, "TENANT", me, "CONNECTED", null);
    UUID seat = seat(room, "LANDLORD", null, "EMPTY", null);
    mvc.perform(
            post("/api/rooms/{id}/invite", room)
                .with(loginAs(me))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + oldPhone + "\",\"consentVersion\":\"v1\"}"))
        .andExpect(status().isOk());
    return seat;
  }

  private ResultActions change(UUID room, UUID memberId, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/counterpart-phone", room)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions answer(UUID seat, UUID memberId, String what, String body)
      throws Exception {
    return mvc.perform(
        post("/api/invitations/{id}/" + what, seat)
            .with(loginAs(memberId))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** 처음 초대 그대로: 옛 번호·링크 하나·문자 대기, 처음 초대의 행동 2개만. */
  private void assertUnchanged(UUID room, UUID seat) {
    assertThat(actions(room)).hasSize(2);
    assertThat(count("state.link", room)).isEqualTo(1);
    UUID link = onlyInviteLink(room);
    assertThat(
            jdbc.queryForObject(
                "SELECT invalidated_at FROM state.link WHERE id = ?", Object.class, link))
        .isNull();
    assertThat(outboxStatus(noticeOf(link))).isEqualTo("PENDING");
    Map<String, Object> row = seatRow(seat);
    assertThat(row.get("status")).isEqualTo("INVITED");
    assertThat(row.get("invite_phone")).isEqualTo(oldPhone);
  }

  private Map<String, Object> seatRow(UUID seat) {
    return jdbc.queryForMap("SELECT * FROM state.party WHERE id = ?", seat);
  }

  private UUID onlyInviteLink(UUID room) {
    return jdbc.queryForObject(
        "SELECT id FROM state.link WHERE room_record_id = ? AND kind = 'INVITE'", UUID.class, room);
  }

  private UUID noticeOf(UUID link) {
    return jdbc.queryForObject(
        "SELECT id FROM state.notice WHERE link_id = ? AND kind = 'INVITE'", UUID.class, link);
  }

  private String statusOf(String table, UUID id) {
    return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
  }

  private String outboxStatus(UUID notice) {
    return jdbc.queryForObject(
        "SELECT status FROM state.outbox WHERE dedupe_key = ?", String.class, "sms:" + notice);
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

  private static String body(String phone, String consentVersion) {
    String phoneJson = phone == null ? "null" : "\"" + phone + "\"";
    return "{\"phone\":" + phoneJson + ",\"consentVersion\":\"" + consentVersion + "\"}";
  }

  private UUID room(String roomStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, ended_at, created_at, version)
        VALUES (?, ?, '302호', ?, 'TENANT',
          CASE WHEN CAST(? AS varchar) = 'LIVING' THEN NULL ELSE CURRENT_TIMESTAMP END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        roomStatus,
        roomStatus);
    rooms.add(id);
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

  /** 하이픈을 넣은 모양(010-1234-5678). 서버는 숫자만 남겨 저장한다. */
  private static String dashed(String digits) {
    return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
  }

  /** 다른 테스트와 겹치지 않는 휴대폰 번호(숫자만). */
  private static String phone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
