package com.chilsuitjo.back.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.dto.LinkRoom;
import com.chilsuitjo.back.link.dto.LinkViewRequest;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import com.chilsuitjo.back.notification.service.NoticeWaitStart.EvidenceNotRecorded;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 링크 열람이 전달 증거가 되는지(06 NOTI-3b, 03 1-1 "타이머 시작점", 05 4장 "집주인 링크 열람 → notice(VIEWED)"). 실제
 * pipeline·잠금·DB로 본다. 링크 내용은 테스트 가짜(열린 호실의 호실 초대)로 두고, 통지·요청 행은 직접 넣는다. 행은 임의 ID라 다른 테스트와 겹치지 않는다.
 */
@Import({TestcontainersConfiguration.class, FakeLinkContentSource.class})
@SpringBootTest
@ActiveProfiles("test")
class LinkViewWaitStartTest {

  private static final Duration RENOTIFY = Duration.ofHours(48);
  private static final Instant SENT = Instant.parse("2026-11-20T01:00:00Z");

  @Autowired private LinkViewService service;
  @Autowired private FakeLinkContentSource content;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void 결과가_아직_없는_보낸_요청_통지는_열람이_되고_기다림이_열람_시각부터_시작한다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");

    Instant before = Instant.now();
    service.view(new LinkViewRequest(fx.token()));

    Map<String, Object> row = notice(notice);
    assertThat(row).containsEntry("status", "VIEWED");
    Instant viewedAt = ((Timestamp) row.get("viewed_at")).toInstant();
    assertThat(viewedAt).isAfterOrEqualTo(before.minusSeconds(1));
    // 업체 결과가 아니므로 전달 시각은 비워 두고, 더 묻지 않는다
    assertThat(row.get("delivered_at")).isNull();
    assertThat(row.get("next_poll_at")).isNull();
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(viewedAt);
    assertThat(requestTime(request, "renotify_due_at")).isEqualTo(viewedAt.plus(RENOTIFY));
    assertThat(viewCount(fx)).isEqualTo(1);
  }

  @Test
  void 이미_전달로_시작한_기다림은_뒤의_열람으로_늦추거나_늘리지_않는다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    Instant delivered = SENT.plusSeconds(30);
    jdbc.update(
        "UPDATE state.response_request SET first_notified_at = ?, renotify_due_at = ? WHERE id = ?",
        Timestamp.from(delivered),
        Timestamp.from(delivered.plus(RENOTIFY)),
        request);
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "DELIVERED");

    service.view(new LinkViewRequest(fx.token()));

    assertThat(notice(notice))
        .containsEntry("status", "VIEWED")
        .containsEntry("delivered_at", Timestamp.from(delivered));
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(delivered);
    assertThat(requestTime(request, "renotify_due_at")).isEqualTo(delivered.plus(RENOTIFY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"REQUESTED", "FAILED", "CANCELLED"})
  void 아직_보내지_않았거나_최종_실패하거나_취소한_통지는_같은_링크를_열어도_그대로다(String status) {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", status);

    service.view(new LinkViewRequest(fx.token()));

    assertThat(notice(notice)).containsEntry("status", status);
    assertThat(requestTime(request, "first_notified_at")).isNull();
    assertThat(viewCount(fx)).isEqualTo(1);
  }

  @Test
  void 다른_링크의_통지와_닫힌_요청은_기다림을_시작하지_않는다() {
    Fixture fx = fixture();
    UUID otherLink = link(fx, null);
    UUID other = request(fx, "WAITING");
    UUID otherNotice = notice(fx, otherLink, other, "REQUEST", "SENT");
    UUID answered = request(fx, "ANSWERED");
    UUID answeredNotice = notice(fx, fx.link(), answered, "REQUEST", "SENT");

    service.view(new LinkViewRequest(fx.token()));

    assertThat(notice(otherNotice)).containsEntry("status", "SENT");
    assertThat(requestTime(other, "first_notified_at")).isNull();
    // 열람은 사실대로 남지만 끝난 요청은 바꾸지 않는다
    assertThat(notice(answeredNotice)).containsEntry("status", "VIEWED");
    assertThat(requestTime(answered, "first_notified_at")).isNull();
  }

  @Test
  void 요청이_없는_초대_문자는_열람만_남는다() {
    Fixture fx = fixture();
    UUID invite = notice(fx, fx.link(), null, "INVITE", "SENT");

    service.view(new LinkViewRequest(fx.token()));

    assertThat(notice(invite)).containsEntry("status", "VIEWED");
  }

  @Test
  void 번호를_고쳐_무효가_된_옛_링크는_열리지_않아_보낸_통지도_요청도_그대로다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE id = ?",
        fx.link());

    assertLinkInvalid(fx);

    assertThat(notice(notice)).containsEntry("status", "SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
    assertThat(viewCount(fx)).isZero();
  }

  @Test
  void 잠근_뒤_정산_완료로_막힌_열람은_증거를_남기지_않는다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    // 미리 거르는 가짜 content는 열린 호실 그대로 두고, 잠근 실제 호실 행만 정산 완료로 바꾼다
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        fx.room());

    assertLinkInvalid(fx);

    assertThat(notice(notice)).containsEntry("status", "SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
    assertThat(viewCount(fx)).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 최근_열람_안이라도_아직_증거가_없는_보낸_문자가_있으면_한_줄을_더_남기고_그_뒤로는_다시_생략한다(boolean sentBeforeFirstView) {
    // false: 첫 열람 뒤에 보낸 문자. true: 첫 열람 전에 업체로 보냈지만 작업자가 결과(SENT)를 늦게 적어 첫 열람이 보지 못한 문자
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    if (!sentBeforeFirstView) {
      jdbc.update(
          "UPDATE state.notice SET sent_at = ?, delivery_check_due_at = ? WHERE id = ?",
          Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now().plus(Duration.ofDays(3))),
          notice);
    }

    service.view(new LinkViewRequest(fx.token()));

    assertThat(viewCount(fx)).isEqualTo(2);
    Timestamp secondAt =
        jdbc.queryForObject(
            "SELECT max(created_at) FROM record.action WHERE type = 'LINK_VIEW' AND link_id = ?",
            Timestamp.class,
            fx.link());
    assertThat(notice(notice))
        .containsEntry("status", "VIEWED")
        .containsEntry("viewed_at", secondAt);
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(secondAt.toInstant());

    // 그 뒤 새로고침: 새 증거가 없어 다시 생략한다(예외 줄의 시각부터 10분)
    service.view(new LinkViewRequest(fx.token()));
    assertThat(viewCount(fx)).isEqualTo(2);
  }

  @Test
  void 업체가_이미_전달을_알린_통지로는_최근_열람_예외를_만들지_않는다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "DELIVERED");

    service.view(new LinkViewRequest(fx.token()));

    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(notice(notice)).containsEntry("status", "DELIVERED");
  }

  @Test
  void 예외_줄은_그_링크의_후보를_모두_처리하고_다른_링크의_통지는_그대로다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID request = request(fx, "WAITING");
    UUID first = notice(fx, fx.link(), request, "REQUEST", "SENT");
    UUID second = notice(fx, fx.link(), request, "RENOTIFY", "SENT");
    UUID delivered = notice(fx, fx.link(), null, "INVITE", "DELIVERED");
    UUID otherLink = notice(fx, link(fx, null), null, "INVITE", "SENT");

    service.view(new LinkViewRequest(fx.token()));

    assertThat(viewCount(fx)).isEqualTo(2);
    assertThat(notice(first)).containsEntry("status", "VIEWED");
    assertThat(notice(second)).containsEntry("status", "VIEWED");
    // 새 줄이 정상으로 남으면 이미 전달된 통지도 지금처럼 열람이 된다(전달 시각은 그대로)
    assertThat(notice(delivered)).containsEntry("status", "VIEWED");
    assertThat(notice(delivered).get("delivered_at")).isNotNull();
    assertThat(notice(otherLink)).containsEntry("status", "SENT");
    String key =
        jdbc.queryForObject(
            "SELECT idempotency_key FROM record.action WHERE type = 'LINK_VIEW' AND link_id = ?"
                + " ORDER BY created_at DESC LIMIT 1",
            String.class,
            fx.link());
    // DB의 ID 순서(uuid 비교)로 가장 앞선 후보
    UUID earliest =
        jdbc.queryForObject(
            "SELECT id FROM state.notice WHERE id IN (?, ?) ORDER BY id LIMIT 1",
            UUID.class,
            first,
            second);
    assertThat(key).isEqualTo("link-view:n:" + earliest);
  }

  @Test
  void 반드시_남길_통지를_열람으로_바꾸지_못하면_예외로_함께_되돌린다() {
    // 링크 열람 증거 키의 저장(LinkViewStateStore): 행동 시각이 그 통지를 보낸 시각보다 앞서면(서버 시계가 뒤로 감) 그 통지를 바꾸지 않는다
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID other = notice(fx, fx.link(), request, "REQUEST", "SENT");
    UUID later = notice(fx, fx.link(), request, "RENOTIFY", "SENT");
    Instant viewAt = Instant.now().minusSeconds(3_600);
    jdbc.update(
        "UPDATE state.notice SET sent_at = ?, delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(viewAt.minusSeconds(60)),
        Timestamp.from(viewAt.plusSeconds(86_400)),
        other);
    jdbc.update(
        "UPDATE state.notice SET sent_at = ?, delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(viewAt.plusSeconds(1)),
        Timestamp.from(viewAt.plusSeconds(86_400)),
        later);
    UUID room =
        jdbc.queryForObject(
            "SELECT room_record_id FROM state.link WHERE id = ?", UUID.class, fx.link());

    assertThatThrownBy(() ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                    status -> waitStart.linkViewed(room, fx.link(), viewAt, later)))
        .isInstanceOf(EvidenceNotRecorded.class);

    // 같은 트랜잭션에서 바꾼 다른 통지·기다림도 되돌아간다
    assertThat(notice(other)).containsEntry("status", "SENT");
    assertThat(notice(later)).containsEntry("status", "SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 새_증거_뒤_동시에_연_두_요청은_한_줄만_더_남긴다() throws Exception {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> views = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        views.add(
            pool.submit(() -> {
              go.await();
              return service.view(new LinkViewRequest(fx.token()));
            }));
      }
      go.countDown();
      for (Future<?> view : views) {
        view.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(viewCount(fx)).isEqualTo(2);
    assertThat(notice(notice)).containsEntry("status", "VIEWED");
  }

  @Test
  void 최근_열람_예외가_있어도_무효가_된_링크는_열리지_않고_보낸_통지도_그대로다() {
    Fixture fx = fixture();
    service.view(new LinkViewRequest(fx.token()));
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE id = ?",
        fx.link());

    assertLinkInvalid(fx);

    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(notice(notice)).containsEntry("status", "SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 열람_뒤에_보낸_문자는_그_열람의_증거가_아니다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    // 잠금을 기다리는 사이 보낸 것처럼 보낸 시각이 열람보다 늦다
    Instant later = Instant.now().plus(Duration.ofHours(1));
    jdbc.update(
        "UPDATE state.notice SET sent_at = ?, next_poll_at = ?, delivery_check_due_at = ? WHERE id = ?",
        Timestamp.from(later),
        Timestamp.from(later.plusSeconds(60)),
        Timestamp.from(later.plus(Duration.ofDays(3))),
        notice);

    service.view(new LinkViewRequest(fx.token()));

    assertThat(viewCount(fx)).isEqualTo(1);
    assertThat(notice(notice)).containsEntry("status", "SENT");
    assertThat(requestTime(request, "first_notified_at")).isNull();
  }

  @Test
  void 열람_시각은_열람_행동의_기록_시각이다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");

    service.view(new LinkViewRequest(fx.token()));

    Timestamp actionAt =
        jdbc.queryForObject(
            "SELECT created_at FROM record.action WHERE type = 'LINK_VIEW' AND link_id = ?",
            Timestamp.class,
            fx.link());
    assertThat(notice(notice)).containsEntry("viewed_at", actionAt);
    assertThat(requestTime(request, "first_notified_at")).isEqualTo(actionAt.toInstant());
  }

  @Test
  void 증거를_적은_뒤_열람_기록_저장이_실패하면_열람과_기다림이_함께_되돌아간다() {
    Fixture fx = fixture();
    UUID request = request(fx, "WAITING");
    UUID notice = notice(fx, fx.link(), request, "REQUEST", "SENT");
    // 접근 검증(증거 적기) 뒤의 행동 저장만 실패하게 한다
    String trigger = "test_fail_link_view_" + fx.link().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION record."
            + trigger
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test'; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + trigger
            + " BEFORE INSERT ON record.action FOR EACH ROW WHEN (NEW.link_id = '"
            + fx.link()
            + "') EXECUTE FUNCTION record."
            + trigger
            + "()");
    try {
      assertThatThrownBy(() -> service.view(new LinkViewRequest(fx.token())))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbc.execute("DROP TRIGGER " + trigger + " ON record.action");
      jdbc.execute("DROP FUNCTION record." + trigger + "()");
    }

    assertThat(notice(notice)).containsEntry("status", "SENT");
    assertThat(notice(notice).get("viewed_at")).isNull();
    assertThat(requestTime(request, "first_notified_at")).isNull();
    assertThat(viewCount(fx)).isZero();
  }

  // ---- 도우미 ----

  private void assertLinkInvalid(Fixture fx) {
    assertThatThrownBy(() -> service.view(new LinkViewRequest(fx.token())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LINK_INVALID));
  }

  private UUID request(Fixture fx, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, 'CORRECTION_CONFIRM', 'TENANT', ?, ?, 0)
        """,
        id,
        fx.room(),
        status,
        Timestamp.from(SENT.minusSeconds(20)));
    return id;
  }

  /** 상태에 맞는 시각 칸을 채운 문자 통지. */
  private UUID notice(Fixture fx, UUID link, UUID request, String kind, String status) {
    UUID id = UUID.randomUUID();
    boolean sent = !status.equals("REQUESTED") && !status.equals("CANCELLED");
    jdbc.update(
        """
        INSERT INTO state.notice (id, room_record_id, response_request_id, link_id,
          recipient_party_id, kind, channel, status, sent_at, delivered_at, failed_at,
          next_poll_at, delivery_check_due_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 'SMS', ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        fx.room(),
        request,
        link,
        fx.party(),
        kind,
        status,
        sent ? Timestamp.from(SENT) : null,
        status.equals("DELIVERED") ? Timestamp.from(SENT.plusSeconds(30)) : null,
        status.equals("FAILED") ? Timestamp.from(SENT.plusSeconds(60)) : null,
        status.equals("SENT") ? Timestamp.from(SENT.plusSeconds(60)) : null,
        sent ? Timestamp.from(SENT.plus(Duration.ofDays(3))) : null,
        Timestamp.from(SENT.minusSeconds(10)));
    return id;
  }

  private Map<String, Object> notice(UUID id) {
    return jdbc.queryForMap(
        "SELECT status, viewed_at, delivered_at, next_poll_at FROM state.notice WHERE id = ?", id);
  }

  private Instant requestTime(UUID id, String column) {
    Timestamp value =
        jdbc.queryForObject(
            "SELECT " + column + " FROM state.response_request WHERE id = ?", Timestamp.class, id);
    return value == null ? null : value.toInstant();
  }

  private int viewCount(Fixture fx) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE type = 'LINK_VIEW' AND link_id = ?",
        Integer.class,
        fx.link());
  }

  /** 열린 호실, 초대된 집주인 자리, 그 자리의 초대 링크. 가짜 content는 열린 호실로 둔다. */
  private Fixture fixture() {
    UUID room = UUID.randomUUID();
    String region = jdbc.queryForObject("SELECT code FROM state.region LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO state.room_record (id, region_code, alias, status, created_by_side,"
            + " created_at, version) VALUES (?, ?, '테스트', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)",
        room,
        region);
    UUID party = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.party (id, room_record_id, side, status, created_at, version)"
            + " VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)",
        party,
        room);
    content.seedRoom(
        room,
        party,
        new LinkRoom(
            PartySide.LANDLORD,
            "서울특별시",
            "강남구",
            "역삼동",
            false,
            false,
            PartyStatus.INVITED,
            RoomStatus.LIVING));
    Fixture partial = new Fixture(room, party, null, null);
    String token = LinkToken.newToken();
    UUID link = link(partial, token);
    return new Fixture(room, party, link, token);
  }

  /** 이 자리의 초대 링크. {@code token}이 없으면 열지 않을 다른 링크다. */
  private UUID link(Fixture fx, String token) {
    UUID member = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        member);
    UUID link = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        link,
        fx.room(),
        fx.party(),
        member,
        LinkToken.hash(token == null ? LinkToken.newToken() : token));
    return link;
  }

  private record Fixture(UUID room, UUID party, UUID link, String token) {}
}
