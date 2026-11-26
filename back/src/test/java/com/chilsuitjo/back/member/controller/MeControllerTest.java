package com.chilsuitjo.back.member.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 06 4-1 {@code GET /api/me}·{@code POST /api/me}. 보안 설정까지 띄워 토큰으로 부른다. 회원은 DB에 바로 넣는다(가입은 {@code
 * SignupControllerTest}가 본다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MeControllerTest {

  private static final String PASSWORD = "homestamp123";

  @Autowired private MockMvc mvc;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    for (UUID id : created) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", id);
      jdbc.update("DELETE FROM state.member WHERE id = ?", id);
    }
  }

  @Test
  void 로그인한_회원은_자기_정보를_가리지_않고_받는다() throws Exception {
    String email = newEmail();
    String phone = newPhone();
    UUID memberId = activeMember(email, phone);

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(memberId.toString()))
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.displayName").value("정서윤"))
        .andExpect(jsonPath("$.phone").value(phone))
        .andExpect(jsonPath("$.smsEnabled").value(true))
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.version").doesNotExist())
        .andExpect(jsonPath("$.status").doesNotExist());
  }

  @Test
  void 로그인_API로_받은_토큰으로_바로_내_정보를_부른다() throws Exception {
    String email = newEmail();
    UUID memberId = activeMember(email, newPhone());
    String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
    String accessToken =
        JsonPath.read(
            mvc.perform(
                    post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.accessToken");

    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(memberId.toString()));
  }

  @Test
  void ACC_3a_다른_계정이_번호를_가져가면_phone은_null이다() throws Exception {
    UUID memberId = activeMember(newEmail(), null);

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phone").value(nullValue()));
  }

  @Test
  void ACC_6_문자를_끈_회원은_smsEnabled가_false다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());
    jdbc.update("UPDATE state.member SET sms_enabled = false WHERE id = ?", memberId);

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.smsEnabled").value(false));
  }

  @Test
  void 토큰이_없으면_LOGIN_REQUIRED() throws Exception {
    expectLoginRequired(mvc.perform(get("/api/me")));
  }

  @Test
  void 틀린_토큰이면_LOGIN_REQUIRED() throws Exception {
    expectLoginRequired(
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")));
  }

  @Test
  void 탈퇴한_회원의_남은_토큰이면_LOGIN_REQUIRED() throws Exception {
    UUID memberId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', ?, ?, 0)",
        memberId,
        now,
        now);
    created.add(memberId);

    expectLoginRequired(mvc.perform(get("/api/me").with(loginAs(memberId))));
  }

  @Test
  void 없는_회원의_토큰이면_LOGIN_REQUIRED() throws Exception {
    expectLoginRequired(mvc.perform(get("/api/me").with(loginAs(UUID.randomUUID()))));
  }

  @Test
  void ACC_4_이름을_바꾸면_바뀐_내_정보를_받고_다시_불러도_같다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"displayName\":\"정하늘\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("정하늘"))
        .andExpect(jsonPath("$.smsEnabled").value(true));

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(jsonPath("$.displayName").value("정하늘"));
  }

  @Test
  void ACC_6_문자_설정만_보내면_이름은_그대로다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"smsEnabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("정서윤"))
        .andExpect(jsonPath("$.smsEnabled").value(false));
    assertThat(smsEnabled(memberId)).isFalse();

    update(memberId, "{\"smsEnabled\":true}").andExpect(jsonPath("$.smsEnabled").value(true));
    assertThat(smsEnabled(memberId)).isTrue();
  }

  @Test
  void 문자를_꺼도_번호는_지우지_않는다() throws Exception {
    String phone = newPhone();
    UUID memberId = activeMember(newEmail(), phone);

    update(memberId, "{\"smsEnabled\":false}").andExpect(jsonPath("$.phone").value(phone));
  }

  @Test
  void 이름과_문자_설정을_함께_바꾼다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"displayName\":\"정하늘\",\"smsEnabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("정하늘"))
        .andExpect(jsonPath("$.smsEnabled").value(false));
  }

  @Test
  void 빈_이름이나_50자를_넘는_이름은_VALIDATION_FAILED이고_바뀌지_않는다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    for (String name : List.of("", "   ", "가".repeat(51))) {
      update(memberId, "{\"displayName\":\"%s\",\"smsEnabled\":false}".formatted(name))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }
    assertThat(smsEnabled(memberId)).isTrue();
    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(jsonPath("$.displayName").value("정서윤"));
  }

  // 05 2-3 이름 변경 내부 기록: 실제로 바뀌었을 때만 회원 ID와 변경 사실, 값은 남기지 않는다(decisions 2026-11-26)

  @Test
  void 이름이_바뀌면_회원_ID와_변경_사실만_로그에_남는다(CapturedOutput output) throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"displayName\":\"정하늘\"}").andExpect(status().isOk());

    assertThat(output.getOut()).contains("이름 변경: 회원 " + memberId).doesNotContain("정하늘");
  }

  @Test
  void 같은_이름이나_문자_설정만_바꾸면_이름_변경_로그가_없다(CapturedOutput output) throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"displayName\":\"정서윤\"}").andExpect(status().isOk());
    update(memberId, "{\"smsEnabled\":false}").andExpect(status().isOk());

    assertThat(output.getOut()).doesNotContain("이름 변경: 회원 " + memberId);
  }

  @Test
  void 수정이_실패하면_이름_변경_로그가_없다(CapturedOutput output) throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());

    update(memberId, "{\"displayName\":\"%s\"}".formatted("가".repeat(51)))
        .andExpect(status().isBadRequest());

    assertThat(output.getOut()).doesNotContain("이름 변경: 회원 " + memberId);
  }

  @Test
  void 이름은_50자까지_받는다() throws Exception {
    UUID memberId = activeMember(newEmail(), newPhone());
    String name = "가".repeat(50);

    update(memberId, "{\"displayName\":\"%s\"}".formatted(name))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value(name));
  }

  @Test
  void 수정에_토큰이_없으면_LOGIN_REQUIRED() throws Exception {
    expectLoginRequired(
        mvc.perform(
            post("/api/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"smsEnabled\":false}")));
  }

  @Test
  void 탈퇴한_회원의_남은_토큰으로_수정하면_LOGIN_REQUIRED() throws Exception {
    UUID memberId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', ?, ?, 0)",
        memberId,
        now,
        now);
    created.add(memberId);

    expectLoginRequired(update(memberId, "{\"displayName\":\"정하늘\"}"));
  }

  private ResultActions update(UUID memberId, String body) throws Exception {
    return mvc.perform(
        post("/api/me")
            .with(loginAs(memberId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private Boolean smsEnabled(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT sms_enabled FROM state.member WHERE id = ?", Boolean.class, memberId);
  }

  private UUID activeMember(String email, String phone) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, phone,"
            + " phone_verified_at, status, created_at, version)"
            + " VALUES (?, ?, ?, '정서윤', ?, ?, 'ACTIVE', ?, 0)",
        id,
        email,
        passwordEncoder.encode(PASSWORD),
        phone,
        phone == null ? null : now,
        now);
    created.add(id);
    return id;
  }

  /** {@code ux_member_phone}에 걸리지 않게 테스트마다 다른 번호. */
  private static String newPhone() {
    return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }

  private static String newEmail() {
    return "me-" + UUID.randomUUID() + "@example.com";
  }

  private static void expectLoginRequired(ResultActions result) throws Exception {
    result
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_REQUIRED.name()));
  }
}
