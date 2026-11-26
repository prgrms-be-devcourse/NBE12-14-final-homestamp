package com.chilsuitjo.back.auth.controller;

import static com.chilsuitjo.back.auth.rule.VerificationPurpose.RESET;
import static com.chilsuitjo.back.auth.rule.VerificationPurpose.SIGNUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.IssuedCode;
import com.chilsuitjo.back.auth.dto.IssuedTokens;
import com.chilsuitjo.back.auth.dto.PasswordResetRequest;
import com.chilsuitjo.back.auth.rule.VerificationPurpose;
import com.chilsuitjo.back.auth.service.AuthTokenService;
import com.chilsuitjo.back.auth.service.PasswordService;
import com.chilsuitjo.back.auth.service.PhoneVerificationService;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 06 4-1 {@code POST /api/me/password}(ACC-4a)·{@code POST /api/auth/reset-password}(ACC-5b). 보안
 * 설정까지 띄우고, 기기는 {@link AuthTokenService#issue}로 만든 묶음이다. 인증 표는 서비스로 만든다(가입 테스트와 같음).
 *
 * <p>"비밀번호가 바뀌었어요" 알림은 아직 없다(#261).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordControllerTest {

  private static final String ORIGIN = "http://localhost:5173";
  private static final String OLD_PASSWORD = "homestamp123";
  private static final String NEW_PASSWORD = "newpass456";

  @Autowired private MockMvc mvc;
  @Autowired private AuthTokenService tokens;
  @Autowired private PasswordService passwords;
  @Autowired private PhoneVerificationService verifications;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> created = new ArrayList<>();
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM state.phone_verification");
    jdbc.update("DELETE FROM state.rate_counter");
  }

  @AfterEach
  void removeMembers() {
    pool.shutdownNow();
    for (UUID id : created) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", id);
      jdbc.update("DELETE FROM state.member WHERE id = ?", id);
    }
  }

  // ACC-4a 비밀번호를 바꾸면 다른 기기는 로그아웃된다

  @Test
  void ACC_4a_바꾸면_새_비밀번호로만_로그인되고_지금_기기는_남고_다른_기기는_로그아웃된다() throws Exception {
    String email = newEmail();
    UUID memberId = member(email, newPhone());
    IssuedTokens here = tokens.issue(memberId);
    IssuedTokens other = tokens.issue(memberId);

    change(here, OLD_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

    login(email, NEW_PASSWORD).andExpect(status().isOk());
    expectProblem(login(email, OLD_PASSWORD), ErrorCode.LOGIN_FAILED);
    mvc.perform(refresh(here.refreshToken())).andExpect(status().isOk());
    expectProblem(mvc.perform(refresh(other.refreshToken())), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 현재_비밀번호가_틀리면_PASSWORD_MISMATCH이고_아무것도_바뀌지_않는다() throws Exception {
    String email = newEmail();
    UUID memberId = member(email, newPhone());
    IssuedTokens here = tokens.issue(memberId);
    IssuedTokens other = tokens.issue(memberId);

    expectProblem(change(here, "wrong12345", NEW_PASSWORD), ErrorCode.PASSWORD_MISMATCH);

    login(email, OLD_PASSWORD).andExpect(status().isOk());
    mvc.perform(refresh(other.refreshToken())).andExpect(status().isOk());
  }

  @Test
  void 현재_비밀번호가_72바이트를_넘어도_서버_오류가_아니라_PASSWORD_MISMATCH() throws Exception {
    IssuedTokens here = tokens.issue(member(newEmail(), newPhone()));

    expectProblem(change(here, "a1" + "가".repeat(30), NEW_PASSWORD), ErrorCode.PASSWORD_MISMATCH);
  }

  @Test
  void 새_비밀번호가_규칙에_어긋나면_VALIDATION_FAILED이고_바뀌지_않는다() throws Exception {
    String email = newEmail();
    IssuedTokens here = tokens.issue(member(email, newPhone()));

    for (String bad : List.of("abc1234", "abcdefgh", "12345678", "a1" + "가".repeat(24))) {
      expectProblem(change(here, OLD_PASSWORD, bad), ErrorCode.VALIDATION_FAILED);
    }
    login(email, OLD_PASSWORD).andExpect(status().isOk());
  }

  @Test
  void 변경에_토큰이_없으면_LOGIN_REQUIRED() throws Exception {
    expectProblem(
        mvc.perform(
            post("/api/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(changeBody(OLD_PASSWORD, NEW_PASSWORD))),
        ErrorCode.LOGIN_REQUIRED);
  }

  // ACC-5b 휴대폰 인증으로 비밀번호를 다시 정하면 모든 기기가 로그아웃된다

  @Test
  void ACC_5b_재설정하면_새_비밀번호로만_로그인되고_모든_기기가_로그아웃된다() throws Exception {
    String email = newEmail();
    String phone = newPhone();
    UUID memberId = member(email, phone);
    IssuedTokens first = tokens.issue(memberId);
    IssuedTokens second = tokens.issue(memberId);

    reset(ticket(phone, RESET), NEW_PASSWORD).andExpect(status().isNoContent());

    login(email, NEW_PASSWORD).andExpect(status().isOk());
    expectProblem(login(email, OLD_PASSWORD), ErrorCode.LOGIN_FAILED);
    expectProblem(mvc.perform(refresh(first.refreshToken())), ErrorCode.REFRESH_REUSED);
    expectProblem(mvc.perform(refresh(second.refreshToken())), ErrorCode.REFRESH_REUSED);
  }

  @Test
  void 같은_표로_두_번_재설정하면_두_번째는_CODE_EXPIRED() throws Exception {
    String phone = newPhone();
    member(newEmail(), phone);
    String ticket = ticket(phone, RESET);

    reset(ticket, NEW_PASSWORD).andExpect(status().isNoContent());
    expectProblem(reset(ticket, "another789"), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 같은_표로_동시에_재설정하면_하나만_성공한다() throws Exception {
    String phone = newPhone();
    member(newEmail(), phone);
    String ticket = ticket(phone, RESET);
    PasswordResetRequest request = new PasswordResetRequest(ticket, NEW_PASSWORD);

    List<Object> results =
        concurrently(
            () -> {
              passwords.reset(request);
              return "ok";
            },
            () -> {
              passwords.reset(request);
              return "ok";
            });

    assertThat(results).filteredOn("ok"::equals).hasSize(1);
    assertThat(results)
        .filteredOn(BusinessException.class::isInstance)
        .singleElement()
        .satisfies(
            e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CODE_EXPIRED));
  }

  @Test
  void 가입_표나_틀린_표로는_재설정할_수_없다() throws Exception {
    String phone = newPhone();
    member(newEmail(), phone);

    expectProblem(reset(ticket(phone, SIGNUP), NEW_PASSWORD), ErrorCode.CODE_EXPIRED);
    expectProblem(reset("not-a-ticket", NEW_PASSWORD), ErrorCode.CODE_EXPIRED);
  }

  @Test
  void 새_비밀번호가_규칙에_어긋나면_VALIDATION_FAILED이고_표는_남는다() throws Exception {
    String phone = newPhone();
    member(newEmail(), phone);
    String ticket = ticket(phone, RESET);

    expectProblem(reset(ticket, "abcdefgh"), ErrorCode.VALIDATION_FAILED);
    reset(ticket, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  // ACC-5c 회원이 없는 번호로는 재설정할 수 없고 가입 여부를 따로 알리지 않는다

  @Test
  void ACC_5c_회원이_없는_번호의_표면_CODE_EXPIRED이고_표는_쓰지_않는다() throws Exception {
    String phone = newPhone();
    String ticket = ticket(phone, RESET);

    expectProblem(reset(ticket, NEW_PASSWORD), ErrorCode.CODE_EXPIRED);
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM state.phone_verification WHERE phone = ?",
                Boolean.class,
                phone))
        .isTrue();
  }

  private ResultActions change(IssuedTokens device, String current, String next) throws Exception {
    return mvc.perform(
        post("/api/me/password")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + device.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(changeBody(current, next)));
  }

  private ResultActions reset(String ticket, String next) throws Exception {
    return mvc.perform(
        post("/api/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"verificationTicket\":\"%s\",\"newPassword\":\"%s\"}".formatted(ticket, next)));
  }

  private ResultActions login(String email, String password) throws Exception {
    return mvc.perform(
        post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refresh(
      String refreshToken) {
    return post("/api/auth/refresh")
        .header(HttpHeaders.ORIGIN, ORIGIN)
        .cookie(new Cookie(AuthTokenController.REFRESH_COOKIE, refreshToken));
  }

  private static String changeBody(String current, String next) {
    return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
  }

  private String ticket(String phone, VerificationPurpose purpose) {
    IssuedCode code = verifications.issue(phone, purpose);
    return verifications.verify(code.verificationId(), code.code()).ticket();
  }

  private UUID member(String email, String phone) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, phone,"
            + " phone_verified_at, status, created_at, version)"
            + " VALUES (?, ?, ?, '정서윤', ?, ?, 'ACTIVE', ?, 0)",
        id,
        email,
        passwordEncoder.encode(OLD_PASSWORD),
        phone,
        now,
        now);
    created.add(id);
    return id;
  }

  private static void expectProblem(ResultActions result, ErrorCode code) throws Exception {
    result
        .andExpect(status().is(code.status().value()))
        .andExpect(jsonPath("$.code").value(code.name()));
  }

  /** {@code ux_member_phone}에 걸리지 않게 테스트마다 다른 번호. */
  private static String newPhone() {
    return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }

  private static String newEmail() {
    return "password-" + UUID.randomUUID() + "@example.com";
  }

  /** 두 일을 거의 동시에 시작하고, 결과 또는 던진 예외를 모은다. */
  @SafeVarargs
  private List<Object> concurrently(Callable<Object>... works) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (Callable<Object> work : works) {
      futures.add(
          pool.submit(() -> {
            start.await();
            try {
              return work.call();
            } catch (RuntimeException e) {
              return e;
            }
          }));
    }
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(30, TimeUnit.SECONDS));
    }
    return results;
  }
}
