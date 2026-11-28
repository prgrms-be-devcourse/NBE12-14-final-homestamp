package com.chilsuitjo.back.member.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 06 CONS-4a·b, 4-1 {@code GET /api/me}의 {@code pendingAcknowledgements}·{@code POST
 * /api/me/agreements}. 이용약관만 새 판({@code 2026-12-01})으로 바꾸고 막기를 켠다. 회원은 옛 판({@code v1}) 세 줄을 확인한 채로
 * DB에 바로 넣는다.
 *
 * <p>확인 기록은 덧붙이기만 해서(트리거) 지울 수 없으므로 회원·기록을 정리하지 않는다. 이메일·번호는 테스트마다 다르다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"terms.gate.enabled=true", "terms.version.terms=2026-12-01"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TermsGateApiTest {

  private static final String NEW_TERMS = "2026-12-01";

  /**
   * 면제 목록에 있지만 아직 컨트롤러가 없는 주소(06 4장 표에만 있음). 컨트롤러를 만들면 아래 테스트가 실패하므로 여기서 빼고, 주소가 06과 같은지 다시 본다. 사진
   * 지우기의 {@code redactedUpload}(가린 사본 등록)를 확인 전에 받을지는 그때 정한다(#338 리뷰 4).
   */
  private static final Set<String> NOT_YET_MAPPED =
      Set.of("/api/me/withdraw", "/api/rooms/*/stop-sharing", "/api/photos/*/erase");

  private static final Path SPEC = Path.of("../docs/rules/06-요구사항-명세.md");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  // 면제 주소 계약(#338 리뷰 4)

  @Test
  void 면제_주소는_06_API_표의_POST_주소이고_컨트롤러가_있거나_아직_만들기_전이다() throws Exception {
    String spec = Files.readString(SPEC.toAbsolutePath().normalize());
    Set<String> posts = postPatterns();

    for (String exempt : TermsGateInterceptor.EXEMPT) {
      if (exempt.endsWith("/**")) {
        continue;
      }
      assertThat(specRow(exempt).matcher(spec).find())
          .as("06 4장 표에 POST %s 줄이 있어야 한다", exempt)
          .isTrue();
      if (NOT_YET_MAPPED.contains(exempt)) {
        assertThat(posts)
            .as("%s 컨트롤러가 생겼으면 NOT_YET_MAPPED에서 빼고 주소를 다시 본다", exempt)
            .doesNotContain(exempt);
      } else {
        assertThat(posts).as("%s 컨트롤러가 있어야 한다", exempt).contains(exempt);
      }
    }
  }

  /** 06 표의 {@code | `POST /api/rooms/{id}/stop-sharing` |} 줄. 면제 주소의 {@code *}는 경로 변수 자리다. */
  private static Pattern specRow(String exempt) {
    String path =
        Arrays.stream(exempt.split("\\*", -1))
            .map(Pattern::quote)
            .collect(Collectors.joining("\\{[^}]+\\}"));
    return Pattern.compile("\\| `POST " + path + "` \\|");
  }

  /** 앱에 있는 POST 주소. 경로 변수는 {@code *}로 바꾼다. */
  private Set<String> postPatterns() {
    return mappings.getHandlerMethods().keySet().stream()
        .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.POST))
        .flatMap(info -> info.getPatternValues().stream())
        .map(p -> p.replaceAll("\\{[^}]+\\}", "*"))
        .collect(Collectors.toSet());
  }

  // GET /api/me 다시 확인할 항목

  @Test
  void 옛_판만_확인했으면_바뀐_이용약관만_다시_확인할_항목으로_받는다() throws Exception {
    UUID memberId = memberWithOldVersions();

    mvc.perform(get("/api/me").with(loginAs(memberId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingAcknowledgements.length()").value(1))
        .andExpect(jsonPath("$.pendingAcknowledgements[0].kind").value("TERMS_AGREED"))
        .andExpect(jsonPath("$.pendingAcknowledgements[0].textVersion").value(NEW_TERMS));
  }

  // CONS-4a 확인 전에는 보기만 한다

  @Test
  void CONS_4a_확인_전에_상태를_바꾸면_TERMS_UPDATE_REQUIRED이고_조회는_된다() throws Exception {
    UUID memberId = memberWithOldVersions();

    createRoom(memberId)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ErrorCode.TERMS_UPDATE_REQUIRED.name()));
    mvc.perform(get("/api/rooms").with(loginAs(memberId))).andExpect(status().isOk());
  }

  @Test
  void CONS_4a_다시_확인하면_막히지_않는다() throws Exception {
    UUID memberId = memberWithOldVersions();

    agree(memberId, "TERMS_AGREED", NEW_TERMS)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingAcknowledgements.length()").value(0));

    // 막히지 않으면 본문 검사(빈 본문)까지 간다
    createRoom(memberId).andExpect(status().isBadRequest());
    assertThat(rows(memberId, "TERMS_AGREED", NEW_TERMS)).isEqualTo(1);
  }

  // CONS-4b 권리 행사와 계정 보안은 확인 전에도 된다

  @Test
  void CONS_4b_내려받기의_원본_사진_주소와_로그아웃은_막지_않는다() throws Exception {
    UUID memberId = memberWithOldVersions();

    // 막히지 않으면 본문 검사(빈 사진 목록)까지 간다
    mvc.perform(
            post("/api/photos/view-urls")
                .with(loginAs(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"photoIds\":[],\"variant\":\"ORIGINAL\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/logout").with(loginAs(memberId)))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  void CONS_4b_확인_전에도_알림_읽음은_막지_않는다() throws Exception {
    UUID memberId = memberWithOldVersions();

    // 막히지 않으면 알림을 찾는 데까지 간다(없는 알림이라 NOT_FOUND, TERMS_UPDATE_REQUIRED가 아님)
    mvc.perform(post("/api/me/notifications/{id}/read", UUID.randomUUID()).with(loginAs(memberId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
  }

  @Test
  void 확인_전에도_문자_끄기는_되고_이름_바꾸기는_막힌다() throws Exception {
    UUID memberId = memberWithOldVersions();

    updateMe(memberId, "{\"smsEnabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.smsEnabled").value(false));
    updateMe(memberId, "{\"displayName\":\"정하늘\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ErrorCode.TERMS_UPDATE_REQUIRED.name()));
    updateMe(memberId, "{\"displayName\":\"정하늘\",\"smsEnabled\":false}")
        .andExpect(status().isForbidden());
    updateMe(memberId, "{\"smsEnabled\":true}").andExpect(status().isForbidden());

    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM state.member WHERE id = ?", String.class, memberId))
        .isEqualTo("정서윤");
  }

  // POST /api/me/agreements

  @Test
  void 같은_판을_다시_확인하면_줄을_더하지_않고_성공한다() throws Exception {
    UUID memberId = memberWithOldVersions();

    agree(memberId, "TERMS_AGREED", NEW_TERMS).andExpect(status().isOk());
    agree(memberId, "TERMS_AGREED", NEW_TERMS).andExpect(status().isOk());
    // 이미 지금 판인 항목을 함께 보내도 줄을 더하지 않는다
    mvc.perform(
            post("/api/me/agreements")
                .with(loginAs(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"acknowledgements\":[{\"kind\":\"AGE_14_CONFIRMED\",\"textVersion\":\"v1\"}]}"))
        .andExpect(status().isOk());

    assertThat(rows(memberId, "TERMS_AGREED", NEW_TERMS)).isEqualTo(1);
    assertThat(rows(memberId, "AGE_14_CONFIRMED", "v1")).isEqualTo(1);
  }

  @Test
  void 지금_판이_아니면_VERSION_CONFLICT이고_남지_않는다() throws Exception {
    UUID memberId = memberWithOldVersions();

    agree(memberId, "TERMS_AGREED", "v1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.VERSION_CONFLICT.name()));
    assertThat(rows(memberId, "TERMS_AGREED", "v1")).isEqualTo(1);
  }

  @Test
  void 같은_종류를_두_번_보내거나_비었으면_VALIDATION_FAILED() throws Exception {
    UUID memberId = memberWithOldVersions();
    String item = "{\"kind\":\"TERMS_AGREED\",\"textVersion\":\"" + NEW_TERMS + "\"}";

    agreeRaw(memberId, "{\"acknowledgements\":[" + item + "," + item + "]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    agreeRaw(memberId, "{\"acknowledgements\":[]}").andExpect(status().isBadRequest());
    assertThat(rows(memberId, "TERMS_AGREED", NEW_TERMS)).isZero();
  }

  @Test
  void 같은_회원의_다시_확인이_동시에_와도_한_줄만_남는다() throws Exception {
    UUID memberId = memberWithOldVersions();
    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(() -> {
              start.await();
              return agree(memberId, "TERMS_AGREED", NEW_TERMS)
                  .andReturn()
                  .getResponse()
                  .getStatus();
            }));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(rows(memberId, "TERMS_AGREED", NEW_TERMS)).isEqualTo(1);
  }

  // 도움

  private ResultActions createRoom(UUID memberId) throws Exception {
    return mvc.perform(
        post("/api/rooms")
            .with(loginAs(memberId))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private ResultActions updateMe(UUID memberId, String body) throws Exception {
    return mvc.perform(
        post("/api/me")
            .with(loginAs(memberId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions agree(UUID memberId, String kind, String version) throws Exception {
    return agreeRaw(
        memberId,
        "{\"acknowledgements\":[{\"kind\":\"%s\",\"textVersion\":\"%s\"}]}"
            .formatted(kind, version));
  }

  private ResultActions agreeRaw(UUID memberId, String body) throws Exception {
    return mvc.perform(
        post("/api/me/agreements")
            .with(loginAs(memberId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** 가입 때처럼 세 항목을 {@code v1}로 확인한 회원. */
  private UUID memberWithOldVersions() {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, status, created_at,"
            + " version) VALUES (?, ?, 'x', '정서윤', 'ACTIVE', ?, 0)",
        id,
        "terms-" + id + "@example.com",
        now);
    for (String kind : List.of("AGE_14_CONFIRMED", "TERMS_AGREED", "PRIVACY_NOTICE_ACK")) {
      jdbc.update(
          "INSERT INTO record.member_acknowledgement"
              + " (id, member_id, kind, text_version, agreed_at, created_at)"
              + " VALUES (?, ?, ?, 'v1', ?, ?)",
          UUID.randomUUID(),
          id,
          kind,
          now,
          now);
    }
    return id;
  }

  private int rows(UUID memberId, String kind, String version) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.member_acknowledgement"
            + " WHERE member_id = ? AND kind = ? AND text_version = ?",
        Integer.class,
        memberId,
        kind,
        version);
  }
}
