package com.chilsuitjo.back.notification.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RenotifyKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 번호를 고쳐 다시 초대할 때 이미 열린 점검 확인 요청(기다림)의 처리(#370 T-2). 실제 {@code invite}·{@code counterpart-phone}
 * API, 실제 전달 결과 처리({@link NoticeWaitStart}), 실제 재통지 작업자({@link RenotifyWorker})로 본다. 문자 보내는 작업자는 꺼서
 * 통지 상태를 이 테스트가 정한다(보냄·업체 결과는 {@link #deliver}로 흉내 냄).
 *
 * <p>이번 범위는 점검 확인 요청뿐이다. 수리 요청은 수리 건 링크 발급 뒤 따로 잇고, 이미 미확인인 요청은 정하기 전이라 바꾸지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {"test.notification.wait-service=jpa", "app.outbox.worker.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReinviteWaitingTest {

  private static final String REGION = "9799999904";
  private static final Duration RENOTIFY = Duration.ofHours(48);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RenotifyWorker worker;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private NoticeRepository notices;
  @Autowired private PlatformTransactionManager txManager;

  /** 새 초대 문자를 넣을 때 실패하게 한다(테스트 전용, 운영 코드에 실패 주입 없음). 다른 통지는 그대로 넣는다. */
  @MockitoSpyBean private NoticeOutbox outbox;

  private volatile boolean failInviteSms;

  private Instant now;
  private UUID me;
  private UUID room;
  private UUID seat;
  private UUID request;

  /**
   * 같은 DB를 쓰는 다른 테스트가 이 호실의 요청·문자를 집지 않게 치운다: 재통지 후보에서 빼고(기한 비움), 보낼 문자 줄을
   * 닫는다(CounterpartPhoneApiTest와 같은 방식).
   */
  @AfterEach
  void cleanUp() {
    jdbc.update(
        "UPDATE state.response_request SET renotify_due_at = NULL WHERE room_record_id = ?", room);
    jdbc.update(
        """
        UPDATE state.outbox SET status = 'DONE'
        WHERE status = 'PENDING'
          AND dedupe_key IN (SELECT 'sms:' || id FROM state.notice WHERE room_record_id = ?)
        """,
        room);
  }

  @BeforeEach
  void setUp() throws Exception {
    doAnswer(
            call -> {
              SmsNoticeCommand command = call.getArgument(0);
              if (failInviteSms
                  && room.equals(command.roomRecordId())
                  && command.kind() == NoticeKind.INVITE) {
                throw new IllegalStateException("테스트가 넣은 실패");
              }
              return call.callRealMethod();
            })
        // 거래 프록시(MANDATORY)를 거치지 않게 프록시 안의 스파이에 건다
        .when(AopTestUtils.<NoticeOutbox>getUltimateTargetObject(outbox))
        .enqueueSms(any());
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '재초대시', '재초대구', '재초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    me = member(phone());
    room = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side, created_at, version)"
            + " VALUES (?, ?, '301호', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        room,
        REGION);
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, member_id, status, connected_at, created_at, version)"
            + " VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        room,
        me);
    seat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', 'EMPTY', CURRENT_TIMESTAMP, 0)",
        seat,
        room);
    // 세입자가 확인을 요청한 입주 점검(항목 없음 = 답할 것이 남음)
    jdbc.update(
        "INSERT INTO state.inspection (id, room_record_id, kind, author_side, status, created_at, version)"
            + " VALUES (?, ?, 'MOVE_IN', 'TENANT', 'REQUESTED', CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        room);
    send("/api/rooms/{id}/invite", key(), phone()).andExpect(status().isOk());
    request =
        jdbc.queryForObject(
            "SELECT id FROM state.response_request WHERE room_record_id = ?", UUID.class, room);
  }

  @Test
  void 옛_번호에_닿은_뒤_번호를_고치면_같은_요청의_기다림이_비고_새_번호에_닿아야_다시_시작한다() throws Exception {
    UUID old = requestNotice();
    deliver(old, now.minus(Duration.ofHours(49)));
    Instant firstNotified = instant("first_notified_at");

    changePhone(key(), phone());

    // 같은 요청, 기한 두 칸만 비움. 옛 통지의 전달 사실과 첫 통지 시각(전달 기록, 05 0장)은 그대로
    Map<String, Object> reset = row();
    assertThat(reset.get("status")).isEqualTo("WAITING");
    assertThat(instant("first_notified_at")).isEqualTo(firstNotified);
    assertThat(reset.get("renotify_due_at")).isNull();
    assertThat(reset.get("unconfirmed_due_at")).isNull();
    assertThat(noticeStatus(old)).isEqualTo("DELIVERED");
    UUID fresh = requestNotice();
    assertThat(fresh).isNotEqualTo(old);
    assertThat(linkOf(fresh)).isNotEqualTo(linkOf(old));
    assertThat(requestCount()).isEqualTo(1);

    // 새 번호에 닿기 전: 재통지 없음. 옛 링크로 늦게 온 열람·전달은 시작점이 아니다
    worker.runOnce(50);
    assertThat(renotices()).isEmpty();
    deliver(old, now.minus(Duration.ofHours(50)));
    assertThat(row().get("renotify_due_at")).isNull();
    assertThat(instant("first_notified_at")).isEqualTo(firstNotified);

    // 새 번호에 닿으면 기한은 그 시각부터(첫 통지 시각은 그대로). 재통지는 이 기회에 한 번
    Instant reached = now.minus(Duration.ofHours(49));
    deliver(fresh, reached);
    assertThat(instant("first_notified_at")).isEqualTo(firstNotified);
    assertThat(instant("renotify_due_at")).isEqualTo(reached.plus(RENOTIFY));
    worker.runOnce(50);
    worker.runOnce(50);
    List<Map<String, Object>> renotices = renotices();
    assertThat(renotices)
        .singleElement()
        .satisfies(n -> assertThat(n.get("link_id")).isEqualTo(linkOf(fresh)));
    assertThat(renotifyKeys()).containsExactly(RenotifyKey.of(request, linkOf(fresh)));
    assertThat(RenotifyKey.of(request, linkOf(fresh))).hasSize(48);
  }

  @Test
  void 옛_번호에_재통지까지_닿았어도_새_번호에는_재통지_기회가_한_번_더_있다() throws Exception {
    UUID old = requestNotice();
    deliver(old, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    UUID oldRenotify = (UUID) renotices().getFirst().get("id");
    deliver(oldRenotify, now.minus(Duration.ofMinutes(10)));
    assertThat(row().get("unconfirmed_due_at")).isNotNull();

    changePhone(key(), phone());

    Map<String, Object> reset = row();
    assertThat(reset.get("first_notified_at")).isNotNull();
    assertThat(reset.get("renotify_due_at")).isNull();
    assertThat(reset.get("unconfirmed_due_at")).isNull();
    // 옛 재통지와 그 전달은 기록으로 남는다
    assertThat(noticeStatus(oldRenotify)).isEqualTo("DELIVERED");

    // 옛 재통지가 늦게 또 닿아도 미확인 기한이 생기지 않는다
    deliver(oldRenotify, now.minus(Duration.ofMinutes(5)));
    assertThat(row().get("unconfirmed_due_at")).isNull();

    UUID fresh = requestNotice();
    deliver(fresh, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    worker.runOnce(50);
    assertThat(renotices()).hasSize(2);
    assertThat(renotices())
        .filteredOn(n -> !n.get("id").equals(oldRenotify))
        .singleElement()
        .satisfies(n -> assertThat(n.get("link_id")).isEqualTo(linkOf(fresh)));
    // 기회마다 키가 다르다(요청 + 그 기회의 링크)
    assertThat(renotifyKeys())
        .containsExactlyInAnyOrder(
            RenotifyKey.of(request, linkOf(old)), RenotifyKey.of(request, linkOf(fresh)));
  }

  @Test
  void 옛_번호에_닿기_전에_번호를_고치면_요청은_그대로_새_번호_통지에_다시_잇는다() throws Exception {
    UUID old = requestNotice();

    changePhone(key(), phone());

    assertThat(row().get("first_notified_at")).isNull();
    UUID fresh = requestNotice();
    assertThat(fresh).isNotEqualTo(old);
    assertThat(requestCount()).isEqualTo(1);
  }

  @Test
  void 같은_번호_고치기를_같은_키로_다시_보내도_통지가_늘지_않고_기다림도_그대로다() throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    String key = key();
    String newPhone = phone();
    changePhone(key, newPhone);
    UUID fresh = requestNotice();
    deliver(fresh, now.minus(Duration.ofHours(1)));
    int before = noticeCount();

    changePhone(key, newPhone);

    assertThat(noticeCount()).isEqualTo(before);
    assertThat(instant("renotify_due_at")).isEqualTo(now.minus(Duration.ofHours(1)).plus(RENOTIFY));
  }

  @Test
  void 번호를_X_Y_X로_고쳐도_처음_X_링크의_늦은_전달은_시작점이_아니다() throws Exception {
    String x = phone();
    UUID first = requestNotice();
    changePhone(key(), phone());
    changePhone(key(), x);
    UUID current = requestNotice();
    assertThat(linkOf(current)).isNotEqualTo(linkOf(first));

    deliver(first, now.minus(Duration.ofHours(49)));
    assertThat(row().get("first_notified_at")).isNull();

    deliver(current, now.minus(Duration.ofHours(49)));
    assertThat(row().get("first_notified_at")).isNotNull();
  }

  @Test
  void 이미_미확인인_요청은_번호를_고쳐도_바꾸지_않는다() throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    jdbc.update("UPDATE state.response_request SET status = 'UNCONFIRMED' WHERE id = ?", request);
    Object first = row().get("first_notified_at");

    changePhone(key(), phone());

    assertThat(row().get("status")).isEqualTo("UNCONFIRMED");
    assertThat(row().get("first_notified_at")).isEqualTo(first);
  }

  @Test
  void 재통지를_만든_뒤_보내기_전에_번호를_고치면_옛_재통지는_취소되고_새_기회에_재통지가_한_번_간다() throws Exception {
    // 작업자가 먼저 잠근 순서: 옛 링크로 재통지를 만든 뒤 번호 고치기
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    UUID oldRenotify = (UUID) renotices().getFirst().get("id");

    changePhone(key(), phone());

    assertThat(noticeStatus(oldRenotify)).isEqualTo("CANCELLED");
    assertThat(row().get("renotify_due_at")).isNull();
    UUID fresh = requestNotice();
    deliver(fresh, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    assertThat(renotices())
        .filteredOn(n -> !n.get("id").equals(oldRenotify))
        .singleElement()
        .satisfies(n -> assertThat(n.get("link_id")).isEqualTo(linkOf(fresh)));
  }

  @Test
  void 번호_고치기와_재통지_작업자가_동시에_돌아도_옛_번호_재통지는_남지_않고_새_기회에_한_번이다() throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> change =
          pool.submit(() -> {
            start.await();
            changePhone(key(), phone());
            return null;
          });
      Future<?> run =
          pool.submit(() -> {
            start.await();
            worker.runOnce(50);
            return null;
          });
      start.countDown();
      change.get(60, TimeUnit.SECONDS);
      run.get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // 어느 쪽이 먼저 잠갔든: 기다림은 비었고, 만들어진 옛 재통지는 보내기 전에 취소됐다
    assertThat(row().get("renotify_due_at")).isNull();
    assertThat(renotices()).allSatisfy(n -> assertThat(n.get("status")).isEqualTo("CANCELLED"));
    int old = renotices().size();
    UUID fresh = requestNotice();
    deliver(fresh, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    worker.runOnce(50);
    assertThat(renotices()).hasSize(old + 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ANSWERED", "WITHDRAWN", "CLOSED"})
  void 끝난_요청은_번호를_고쳐도_다시_열지_않는다(String status) throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    jdbc.update(
        "UPDATE state.response_request SET status = ?,"
            + " closed_at = CASE WHEN CAST(? AS varchar) = 'CLOSED' THEN CURRENT_TIMESTAMP END,"
            + " closed_reason = CASE WHEN CAST(? AS varchar) = 'CLOSED' THEN 'CLOSED_BY_BOTH' END"
            + " WHERE id = ?",
        status,
        status,
        status,
        request);
    Map<String, Object> before = row();

    changePhone(key(), phone());

    assertThat(row()).isEqualTo(before);
  }

  // ---- 같은 시각 (7-1) ----

  @Test
  void 새_기회의_통지가_옛_재통지와_같은_시각에_만들어져도_새_기회에_재통지가_간다() throws Exception {
    // 고정 시계·낮은 정밀도에서 새 기회의 요청 통지와 옛 재통지의 created_at이 같아도(둘 다 지금 기한 전): 후보 조회는 같은 시각을 옛 기회로
    // 넣고(>=), 잠근 뒤 확인은 시각이 아니라 링크(REINVITED)로 본다
    UUID old = requestNotice();
    deliver(old, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    UUID oldRenotify = (UUID) renotices().getFirst().get("id");
    changePhone(key(), phone());
    UUID fresh = requestNotice();
    deliver(fresh, now.minus(Duration.ofHours(49)));
    jdbc.update(
        "UPDATE state.notice SET created_at = (SELECT created_at FROM state.notice WHERE id = ?)"
            + " WHERE id = ?",
        oldRenotify,
        fresh);
    // 옛 기회의 요청 통지는 실제로 그보다 앞서 만들어졌다(처음 초대 → 번호 고치기)
    jdbc.update(
        "UPDATE state.notice SET created_at = ? WHERE id = ?",
        Timestamp.from(now.minus(Duration.ofHours(100))),
        old);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT created_at) FROM state.notice WHERE id IN (?, ?)",
                Integer.class,
                oldRenotify,
                fresh))
        .isEqualTo(1);

    worker.runOnce(50);
    worker.runOnce(50);

    assertThat(renotices())
        .filteredOn(n -> !n.get("id").equals(oldRenotify))
        .singleElement()
        .satisfies(n -> assertThat(n.get("link_id")).isEqualTo(linkOf(fresh)));
  }

  @Test
  void 같은_기회의_재통지는_요청_통지와_같은_시각이어도_한_번이다() {
    // 같은 기회의 요청 통지와 재통지가 같은 시각이고 둘 다 지금 기한 전이면 후보 조회는 옛 기회로 보고 넣는다(>=, 거친 거르기). 잠근 뒤 확인이 지금
    // 기회의 재통지(링크가 REINVITED 아님)를 보고 건너뛰어 두 번 가지 않는다
    UUID notice = requestNotice();
    deliver(notice, now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    UUID renotify = (UUID) renotices().getFirst().get("id");
    jdbc.update(
        "UPDATE state.notice SET created_at = ? WHERE id IN (?, ?)",
        Timestamp.from(now.minus(Duration.ofHours(2))),
        notice,
        renotify);

    worker.runOnce(50);

    assertThat(renotices()).hasSize(1);
    assertThat(renotifyKeys()).hasSize(1);
  }

  // ---- 잠금 순서를 정해 둔 경합 (7-2) ----

  @Test
  void 번호_고치기가_먼저_잠그면_기다리던_작업자는_잠근_뒤_다시_읽고_재통지하지_않는다() throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    // 작업자는 잠그기 전에 후보(기한 지남)와 키를 이미 읽은 상태로 기다린다
    runInLockOrder(() -> changePhone(key(), phone()), () -> worker.runOnce(50));

    assertThat(renotices()).isEmpty();
    assertThat(row().get("renotify_due_at")).isNull();
    assertThat(row().get("first_notified_at")).isNotNull();
  }

  @Test
  void 작업자가_먼저_잠그면_옛_링크_재통지를_만들고_뒤의_번호_고치기가_보내기_전에_취소한다() throws Exception {
    UUID old = requestNotice();
    deliver(old, now.minus(Duration.ofHours(49)));

    runInLockOrder(() -> worker.runOnce(50), () -> changePhone(key(), phone()));

    assertThat(renotices())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.get("link_id")).isEqualTo(linkOf(old));
          assertThat(n.get("status")).isEqualTo("CANCELLED");
        });
    assertThat(row().get("renotify_due_at")).isNull();
    // 새 기회에는 한 번 더
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    worker.runOnce(50);
    assertThat(renotices()).hasSize(2);
  }

  // ---- 실제 롤백 (7-3·7-4) ----

  @Test
  void 번호_고치기가_새_초대_문자에서_실패하면_한_트랜잭션이_모두_되돌아가고_같은_키로_다시_하면_된다() throws Exception {
    deliver(requestNotice(), now.minus(Duration.ofHours(49)));
    Map<String, Object> before = snapshot();
    String key = key();
    String newPhone = phone();
    failInviteSms = true;
    try {
      send("/api/rooms/{id}/counterpart-phone", key, newPhone)
          .andExpect(status().isInternalServerError());
    } finally {
      failInviteSms = false;
    }

    // 실제 DB에서: 번호·자리·초대 회차, 링크 무효, 문자 취소, 기다림·기한, 새 링크·통지, 행동 기록이 모두 그대로
    assertThat(snapshot()).isEqualTo(before);

    // 실패한 시도는 멱등 키도 남기지 않아 같은 키로 다시 하면 처리된다
    changePhone(key, newPhone);
    assertThat(row().get("renotify_due_at")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT invite_phone FROM state.party WHERE id = ?", String.class, seat))
        .isEqualTo(newPhone);
  }

  // ---- 도우미 ----

  private void changePhone(String key, String phone) throws Exception {
    send("/api/rooms/{id}/counterpart-phone", key, phone).andExpect(status().isOk());
  }

  private ResultActions send(String path, String key, String phone) throws Exception {
    return mvc.perform(
        post(path, room)
            .with(loginAs(me))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"" + phone + "\",\"consentVersion\":\"v1\"}"));
  }

  /** 업체가 전달됐다고 알린 것처럼: 통지를 전달됨으로 바꾸고 같은 트랜잭션에서 기다림 처리를 부른다(전달 결과 작업자와 같은 순서). */
  private void deliver(UUID noticeId, Instant at) {
    new TransactionTemplate(txManager)
        .executeWithoutResult(tx -> {
          waitStart.lockRoomOf(noticeId);
          // 새 기회의 요청·초대 통지는 실제로 옛 재통지보다 뒤에 닿는다. 이 테스트는 시각을 앞당겨 쓰므로 그 순서를 맞춘다(옛 재통지를 닿은 시각 1분 전으로)
          jdbc.update(
              """
              UPDATE state.notice SET created_at = ?
              WHERE kind = 'RENOTIFY' AND created_at > ?
                AND response_request_id = (
                  SELECT response_request_id FROM state.notice
                  WHERE id = ? AND kind IN ('REQUEST', 'INVITE'))
              """,
              Timestamp.from(at.minusSeconds(60)),
              Timestamp.from(at),
              noticeId);
          Notice notice = notices.findForUpdate(noticeId).orElseThrow();
          jdbc.update(
              "UPDATE state.notice SET status = 'DELIVERED',"
                  + " sent_at = COALESCE(sent_at, ?), delivered_at = COALESCE(delivered_at, ?)"
                  + " WHERE id = ?",
              Timestamp.from(at),
              Timestamp.from(at),
              noticeId);
          // 보낸 문자 줄은 닫힌다(실제 작업자는 보낸 뒤 DONE). 남아 있으면 번호 고치기의 문자 취소가 어긋남으로 본다
          jdbc.update(
              "UPDATE state.outbox SET status = 'DONE' WHERE dedupe_key = ? AND status = 'PENDING'",
              "sms:" + noticeId);
          waitStart.reached(notice, at);
        });
  }

  @FunctionalInterface
  private interface Work {
    void run() throws Exception;
  }

  /**
   * 테스트가 호실 행을 잠가 둔 채로 {@code first}를 띄워 그 잠금을 기다리는 것을 DB에서 본 뒤 {@code second}를 띄우고, 둘 다 기다리면 놓는다.
   * PostgreSQL은 같은 행을 기다리는 쪽을 온 순서대로 들이므로 {@code first}가 먼저 잠근다(시간으로 순서를 맞추지 않음,
   * SmsCancellationServiceTest와 같은 방식).
   */
  private void runInLockOrder(Work first, Work second) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger holderPid = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      Future<?> holder =
          pool.submit(() -> {
            new TransactionTemplate(txManager)
                .executeWithoutResult(tx -> {
                  jdbc.queryForList(
                      "SELECT id FROM state.room_record WHERE id = ? FOR UPDATE", room);
                  holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                  locked.countDown();
                  try {
                    release.await(60, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
            return null;
          });
      if (!locked.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("테스트가 호실을 잠그지 못했어요");
      }
      Future<?> a =
          pool.submit(() -> {
            first.run();
            return null;
          });
      awaitRoomLockWaiters(holderPid.get(), 1);
      Future<?> b =
          pool.submit(() -> {
            second.run();
            return null;
          });
      awaitRoomLockWaiters(holderPid.get(), 2);
      release.countDown();
      holder.get(60, TimeUnit.SECONDS);
      a.get(60, TimeUnit.SECONDS);
      b.get(60, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * 테스트가 쥔 호실 잠금({@code holder} 연결) 뒤에 줄 선 연결이 {@code count}개가 될 때까지 DB를 본다. 첫 대기자는 {@code holder}에
   * 막히고 둘째는 첫 대기자(행 잠금 대기열)에 막히므로 둘 다 센다. 같은 DB의 다른 연결은 세지 않는다.
   */
  private void awaitRoomLockWaiters(int holder, int count) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              """
              WITH w AS (
                SELECT pid, pg_blocking_pids(pid) AS blockers FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock')
              SELECT count(*) FROM w
              WHERE ? = ANY(blockers)
                 OR blockers && (SELECT coalesce(array_agg(pid), '{}') FROM w WHERE ? = ANY(blockers))
              """,
              Integer.class,
              holder,
              holder);
      if (waiting != null && waiting >= count) {
        return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
    throw new AssertionError("호실 잠금 뒤에 줄 선 연결이 " + count + "개가 되지 않았어요");
  }

  /** 번호 고치기가 바꿀 수 있는 이 호실의 모든 것. */
  private Map<String, Object> snapshot() {
    return Map.of(
        "party",
        jdbc.queryForList(
            "SELECT id, status, invite_phone, invite_id FROM state.party WHERE room_record_id = ?"
                + " ORDER BY id",
            room),
        "link",
        jdbc.queryForList(
            "SELECT id, invalidated_at, invalidated_reason FROM state.link WHERE room_record_id = ?"
                + " ORDER BY id",
            room),
        "notice",
        jdbc.queryForList(
            "SELECT id, kind, status, link_id FROM state.notice WHERE room_record_id = ? ORDER BY id",
            room),
        "outbox",
        jdbc.queryForList(
            "SELECT o.dedupe_key, o.status FROM state.outbox o JOIN state.notice n"
                + " ON o.dedupe_key = 'sms:' || n.id WHERE n.room_record_id = ? ORDER BY 1",
            room),
        "request",
        row(),
        "actions",
        jdbc.queryForList(
            "SELECT room_seq, type FROM record.action WHERE room_record_id = ? ORDER BY room_seq",
            room));
  }

  /** 지금 열려 있는 요청 통지(가장 최근). */
  private UUID requestNotice() {
    return jdbc.queryForObject(
        "SELECT id FROM state.notice WHERE response_request_id = ? AND kind IN ('REQUEST', 'INVITE')"
            + " ORDER BY created_at DESC, id DESC LIMIT 1",
        UUID.class,
        request);
  }

  private UUID linkOf(UUID notice) {
    return jdbc.queryForObject("SELECT link_id FROM state.notice WHERE id = ?", UUID.class, notice);
  }

  private String noticeStatus(UUID notice) {
    return jdbc.queryForObject(
        "SELECT status FROM state.notice WHERE id = ?", String.class, notice);
  }

  private int noticeCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.notice WHERE room_record_id = ?", Integer.class, room);
  }

  private int requestCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.response_request WHERE room_record_id = ?",
        Integer.class,
        room);
  }

  private List<Map<String, Object>> renotices() {
    return jdbc.queryForList(
        "SELECT id, link_id, status FROM state.notice WHERE response_request_id = ? AND kind = 'RENOTIFY'"
            + " ORDER BY created_at",
        request);
  }

  private List<String> renotifyKeys() {
    return jdbc.queryForList(
        "SELECT idempotency_key FROM record.action WHERE room_record_id = ? AND type = 'RENOTIFY'",
        String.class,
        room);
  }

  private Map<String, Object> row() {
    return jdbc.queryForMap(
        "SELECT status, first_notified_at, renotify_due_at, unconfirmed_due_at"
            + " FROM state.response_request WHERE id = ?",
        request);
  }

  private Instant instant(String column) {
    return ((Timestamp) row().get(column)).toInstant();
  }

  private UUID member(String verifiedPhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, display_name, phone, phone_verified_at, status, created_at, version)"
            + " VALUES (?, '김하늘', ?, CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id,
        verifiedPhone);
    return id;
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String phone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
