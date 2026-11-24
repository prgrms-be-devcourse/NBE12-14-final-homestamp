package com.chilsuitjo.back.member.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 06 4-1 {@code GET /api/me}. 보안 설정까지 띄워 토큰으로 부른다. 회원은 DB에 바로 넣는다(가입은 {@code SignupControllerTest}가
 * 본다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
