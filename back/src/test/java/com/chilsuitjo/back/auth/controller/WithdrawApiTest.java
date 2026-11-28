package com.chilsuitjo.back.auth.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.service.AuthTokenService;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.member.service.MemberLoginService;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 06 4-1 {@code POST /api/me/withdraw}(KEEP-4c·4d·4f, 03 L "탈퇴", #424). 보안 설정·실제 DB 함수({@code
 * record.purge_member_acknowledgements})까지 띄운다.
 *
 * <p>아직 하지 않는 것(#424): 열린 확인 요청 닫기, 보낸 초대 링크 무효, 상대 알림, 초안·혼자 쓴 호실 삭제. 그래서 여기서 보지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawApiTest {

  private static final String PASSWORD = "homestamp123";

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). 다른 이름을 쓰면 법정동 목록 테스트에 시·도가 둘로 보인다. */
  private static final String REGION = "9799999901";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AuthTokenService tokens;
  @Autowired private MemberLoginService memberLogins;

  private final List<UUID> created = new ArrayList<>();
  private String email;
  private String phone;
  private UUID me;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    email = "withdraw-" + UUID.randomUUID() + "@example.com";
    phone = newPhone();
    me = member(email, phone);
  }

  /** 토큰 행은 다른 테스트({@code AuthTokenServiceTest})가 전체 행 수를 세므로 남기지 않는다. */
  @AfterEach
  void removeTokens() {
    for (UUID id : created) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", id);
    }
  }

  // ---- 비밀번호 다시 입력(KEEP-4c) ----

  @Test
  void KEEP_4c_틀린_비밀번호면_PASSWORD_MISMATCH이고_아무것도_바뀌지_않는다() throws Exception {
    acknowledge(me);
    UUID room = room();
    seat(room, "TENANT", me, "CONNECTED");

    expectProblem(withdraw(me, "{\"password\":\"wrong-pass1\"}"), ErrorCode.PASSWORD_MISMATCH);

    assertThat(memberRow(me).get("status")).isEqualTo("ACTIVE");
    assertThat(memberRow(me).get("email")).isEqualTo(email);
    assertThat(acknowledgements(me)).isOne();
    assertThat(seatStatus(room, "TENANT")).isEqualTo("CONNECTED");
  }

  @Test
  void KEEP_4c_비밀번호_없이_탈퇴하면_PASSWORD_MISMATCH() throws Exception {
    expectProblem(withdraw(me, "{}"), ErrorCode.PASSWORD_MISMATCH);
    expectProblem(withdraw(me, "{\"password\":\"\"}"), ErrorCode.PASSWORD_MISMATCH);
    expectProblem(
        mvc.perform(post("/api/me/withdraw").with(loginAs(me))), ErrorCode.PASSWORD_MISMATCH);

    assertThat(memberRow(me).get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void 로그인하지_않았으면_LOGIN_REQUIRED() throws Exception {
    expectProblem(
        mvc.perform(
            post("/api/me/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PASSWORD))),
        ErrorCode.LOGIN_REQUIRED);
  }

  // ---- 회원 가림·확인 기록·토큰(03 L, KEEP-4f) ----

  @Test
  void 탈퇴하면_신원_칸을_지우고_행은_남기며_다시_로그인할_수_없다() throws Exception {
    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    Map<String, Object> row = memberRow(me);
    assertThat(row.get("status")).isEqualTo("WITHDRAWN");
    assertThat(row.get("withdrawn_at")).isNotNull();
    assertThat(row.get("email")).isNull();
    assertThat(row.get("password_hash")).isNull();
    assertThat(row.get("display_name")).isNull();
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("phone_verified_at")).isNull();
    assertThat(row.get("sms_enabled")).isEqualTo(false);
    assertThat(memberLogins.authenticate(email, PASSWORD)).isEmpty();
  }

  @Test
  void KEEP_4f_확인_기록은_삭제_기록을_남기고_지워진다() throws Exception {
    acknowledge(me);
    acknowledge(me);
    UUID[] ids =
        jdbc.queryForList(
                "SELECT id FROM record.member_acknowledgement WHERE member_id = ?", UUID.class, me)
            .toArray(UUID[]::new);

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    assertThat(acknowledgements(me)).isZero();
    for (UUID id : ids) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT reason FROM log.deletion_log"
                      + " WHERE target_kind = 'MEMBER_ACKNOWLEDGEMENT' AND target_id = ?",
                  String.class,
                  id))
          .isEqualTo("WITHDRAWAL");
    }
  }

  @Test
  void 모든_기기의_리프레시_토큰을_폐기한다() throws Exception {
    tokens.issue(me);
    tokens.issue(me);

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.refresh_token WHERE member_id = ? AND revoked_at IS NULL",
                Integer.class,
                me))
        .isZero();
  }

  @Test
  void 이미_탈퇴했으면_남은_토큰으로_다시_탈퇴해도_LOGIN_REQUIRED() throws Exception {
    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    expectProblem(withdraw(me, body(PASSWORD)), ErrorCode.LOGIN_REQUIRED);
    expectProblem(mvc.perform(get("/api/me").with(loginAs(me))), ErrorCode.LOGIN_REQUIRED);
  }

  @Test
  void KEEP_4d_지운_이메일과_번호로_새_계정을_만들어도_옛_호실은_비어_있다() throws Exception {
    UUID room = room();
    seat(room, "TENANT", me, "CONNECTED");
    seat(room, "LANDLORD", member(newEmail(), newPhone()), "CONNECTED");

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());
    UUID again = member(email, phone);

    mvc.perform(get("/api/rooms").with(loginAs(again)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    expectProblem(
        mvc.perform(get("/api/rooms/{id}", room).with(loginAs(again))), ErrorCode.NOT_FOUND);
  }

  // ---- 호실 자리와 접근 차단(03 B "탈퇴") ----

  @Test
  void 연결된_자리는_탈퇴함이_되고_공유를_그만둔_시각은_남는다() throws Exception {
    UUID shared = room();
    seat(shared, "TENANT", me, "CONNECTED");
    seat(shared, "LANDLORD", member(newEmail(), newPhone()), "CONNECTED");
    UUID stopped = room();
    seat(stopped, "TENANT", me, "CONNECTED");
    seat(stopped, "LANDLORD", member(newEmail(), newPhone()), "CONNECTED");
    Instant stoppedAt = Instant.parse("2026-11-20T00:00:00Z");
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        Timestamp.from(stoppedAt),
        stopped);

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    assertThat(seatStatus(shared, "TENANT")).isEqualTo("WITHDRAWN");
    assertThat(seatStatus(shared, "LANDLORD")).isEqualTo("CONNECTED");
    Map<String, Object> mine =
        jdbc.queryForMap(
            "SELECT * FROM state.party WHERE room_record_id = ? AND side = 'TENANT'", stopped);
    assertThat(mine.get("status")).isEqualTo("WITHDRAWN");
    assertThat(mine.get("member_id")).isEqualTo(me);
    assertThat(((Timestamp) mine.get("sharing_stopped_at")).toInstant()).isEqualTo(stoppedAt);
    // 행동 기록이 아니다(05 2-3 "계정(호실 밖)")
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE room_record_id IN (?, ?)",
                Integer.class,
                shared,
                stopped))
        .isZero();
  }

  @Test
  void 탈퇴_뒤_남은_액세스_토큰으로는_호실을_만들_수_없다() throws Exception {
    // 탈퇴한 회원의 연결된 자리가 새로 생기지 않게 호실 만들기도 회원 행을 공유 잠금하고 본다(#424 리뷰)
    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    expectProblem(
        mvc.perform(
            post("/api/rooms")
                .with(loginAs(me))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"side\":\"TENANT\",\"regionCode\":\"%s\",\"alias\":\"302호\"}"
                        .formatted(REGION))),
        ErrorCode.LOGIN_REQUIRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.party WHERE member_id = ?", Integer.class, me))
        .isZero();
  }

  @Test
  void 탈퇴_뒤_남은_액세스_토큰으로는_호실을_읽을_수_없고_목록에서도_빠진다() throws Exception {
    UUID room = room();
    seat(room, "TENANT", me, "CONNECTED");
    UUID landlord = member(newEmail(), newPhone());
    seat(room, "LANDLORD", landlord, "CONNECTED");

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    expectProblem(mvc.perform(get("/api/rooms/{id}", room).with(loginAs(me))), ErrorCode.NOT_FOUND);
    expectProblem(
        mvc.perform(get("/api/rooms/{id}/parts", room).with(loginAs(me))), ErrorCode.NOT_FOUND);
    mvc.perform(get("/api/rooms").with(loginAs(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    // 남은 사람은 그대로 본다. 상대 자리는 탈퇴함이다
    mvc.perform(get("/api/rooms/{id}", room).with(loginAs(landlord)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.counterpartStatus").value("WITHDRAWN"));
  }

  @Test
  void KEEP_4a_상대가_연결된_적_없는_호실의_초대는_받은_사람에게서_사라지고_연결할_수_없다() throws Exception {
    UUID room = room();
    seat(room, "TENANT", me, "CONNECTED");
    String inviteePhone = newPhone();
    UUID invitee = member(newEmail(), inviteePhone);
    UUID invited = seatInvited(room, "LANDLORD", inviteePhone);
    mvc.perform(get("/api/invitations").with(loginAs(invitee)))
        .andExpect(jsonPath("$.length()").value(1));

    withdraw(me, body(PASSWORD)).andExpect(status().isNoContent());

    mvc.perform(get("/api/invitations").with(loginAs(invitee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    expectProblem(
        mvc.perform(
            post("/api/invitations/{id}/connect", invited)
                .with(loginAs(invitee))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consentVersion\":\"v1\"}")),
        ErrorCode.NOT_FOUND);
    expectProblem(
        mvc.perform(
            post("/api/invitations/{id}/reject", invited)
                .with(loginAs(invitee))
                .header("Idempotency-Key", UUID.randomUUID().toString())),
        ErrorCode.NOT_FOUND);
    assertThat(seatStatus(room, "LANDLORD")).isEqualTo("INVITED");
  }

  // ---- 준비물 ----

  private ResultActions withdraw(UUID memberId, String json) throws Exception {
    return mvc.perform(
        post("/api/me/withdraw")
            .with(loginAs(memberId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private static String body(String password) {
    return "{\"password\":\"%s\"}".formatted(password);
  }

  private static void expectProblem(ResultActions result, ErrorCode code) throws Exception {
    result
        .andExpect(status().is(code.status().value()))
        .andExpect(jsonPath("$.code").value(code.name()));
  }

  private UUID member(String memberEmail, String memberPhone) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, phone,"
            + " phone_verified_at, status, created_at, version)"
            + " VALUES (?, ?, ?, '정서윤', ?, ?, 'ACTIVE', ?, 0)",
        id,
        memberEmail,
        passwordEncoder.encode(PASSWORD),
        memberPhone,
        now,
        now);
    created.add(id);
    return id;
  }

  private void acknowledge(UUID memberId) {
    jdbc.update(
        "INSERT INTO record.member_acknowledgement (id, member_id, kind, text_version, agreed_at,"
            + " created_at) VALUES (?, ?, 'TERMS_AGREED', '2026-11-12', CURRENT_TIMESTAMP,"
            + " CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        memberId);
  }

  private Integer acknowledgements(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.member_acknowledgement WHERE member_id = ?",
        Integer.class,
        memberId);
  }

  private Map<String, Object> memberRow(UUID memberId) {
    return jdbc.queryForMap("SELECT * FROM state.member WHERE id = ?", memberId);
  }

  private UUID room() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, '302호', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION);
    return id;
  }

  private void seat(UUID room, String side, UUID memberId, String seatStatus) {
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
        seatStatus);
  }

  private UUID seatInvited(UUID room, String side, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, ?, ?, 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        invitePhone);
    return id;
  }

  private String seatStatus(UUID room, String side) {
    return jdbc.queryForObject(
        "SELECT status FROM state.party WHERE room_record_id = ? AND side = ?",
        String.class,
        room,
        side);
  }

  /** {@code ux_member_phone}에 걸리지 않게 테스트마다 다른 번호. */
  private static String newPhone() {
    return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }

  private static String newEmail() {
    return "withdraw-" + UUID.randomUUID() + "@example.com";
  }
}
