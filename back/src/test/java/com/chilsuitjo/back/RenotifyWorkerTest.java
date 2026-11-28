package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.repository.NoticeRepository;
import com.chilsuitjo.back.notification.repository.RenotifyStateStore;
import com.chilsuitjo.back.notification.repository.ResponseRequestRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RenotifyKey;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import com.chilsuitjo.back.notification.service.NoticeWaitStart;
import com.chilsuitjo.back.notification.service.RenotifyWorker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재통지 작업자(06 NOTI-5a·OPS-1a, 03 1-1). 실제 pipeline·DB로 돈다. 기한은 지금 시각 기준으로 적고, 결과는 이 테스트가 만든 호실의 행만
 * 본다(같은 DB의 다른 테스트 행이 후보로 함께 처리될 수 있음).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public",
      "app.outbox.worker.enabled=false"
    })
@ActiveProfiles("test")
class RenotifyWorkerTest {

  /** 다른 테스트와 겹치지 않는 지역 코드. RoomQueryApiTest의 9999999993과 겹치면 먼저 넣은 시·도 이름이 남아 그쪽 검증이 깨진다. */
  private static final String REGION = "9999999991";

  private static final Duration UNCONFIRMED = Duration.ofHours(24);

  @Autowired private RenotifyWorker worker;
  @Autowired private NoticeWaitStart waitStart;
  @Autowired private NoticeRepository notices;
  @Autowired private ResponseRequestRepository requests;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private Instant now;
  private UUID room;
  private UUID landlord;

  @BeforeEach
  void setUp() {
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES (?, '테스트', '재통지동') ON CONFLICT DO NOTHING",
        REGION);
    room = room("LIVING");
    landlord = seat(room, "LANDLORD", "INVITED");
  }

  // ---- 대상 ----

  @Test
  void 저장된_재통지_기한이_지난_기다림에는_같은_자리_같은_링크로_재통지_한_통과_서버_행동이_남는다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    List<Map<String, Object>> renotices = renotices(request);
    assertThat(renotices).hasSize(1);
    assertThat(renotices.getFirst())
        .containsEntry("status", "REQUESTED")
        .containsEntry("channel", "SMS")
        .containsEntry("recipient_party_id", landlord)
        .containsEntry("link_id", link);
    assertThat(outboxRows((UUID) renotices.getFirst().get("id"))).isEqualTo(1);
    List<Map<String, Object>> actions = renotifyActions(room);
    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst())
        .containsEntry("actor_key", "server")
        .containsEntry("idempotency_key", RenotifyKey.of(request, link))
        .containsEntry("response_request_id", request)
        .containsEntry("actor_side", null);
    // 재통지를 만든 것만으로는 미확인 기한이 없고 기한도 그대로다(06 0-1 저장한 기한)
    Map<String, Object> row = requestRow(request);
    assertThat(row).containsEntry("status", "WAITING").containsEntry("unconfirmed_due_at", null);
    assertThat(((Timestamp) row.get("renotify_due_at")).toInstant())
        .isEqualTo(now.minusSeconds(60));
  }

  @Test
  void 기한_전이면_재통지가_없다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.plusSeconds(600));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
    assertThat(renotifyActions(room)).isEmpty();
  }

  @Test
  void 첫_전달_증거가_없는_요청은_기한이_없어_재통지가_없다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = request(room, "TENANT", "WAITING", null, null);
    // 보냈지만 아직 닿지 않음
    reachedNotice(request, landlord, link, "REQUEST", "SENT");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ANSWERED", "WITHDRAWN", "UNCONFIRMED", "CLOSED"})
  void 기다림이_아닌_요청은_기한이_지나도_재통지가_없다(String status) {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", status, now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  // ---- 멱등 ----

  @Test
  void 재통지_뒤_같은_기회에서_기한을_다시_정한_요청은_후보가_아니다() {
    // 고친 내용 확인을 재통지 뒤(닿기 전) 또 고치면 recount가 재통지 기한을 늦춘다. 새 요청·초대 통지가 없으니 같은 기회라 후보에 다시 오지 않는다(#370
    // T-2)
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    assertThat(renotices(request)).hasSize(1);
    jdbc.update(
        "UPDATE notice SET created_at = ? WHERE response_request_id = ? AND kind = 'RENOTIFY'",
        Timestamp.from(now.minus(Duration.ofHours(10))),
        request);
    jdbc.update(
        "UPDATE response_request SET renotify_due_at = ? WHERE id = ?",
        Timestamp.from(now.minus(Duration.ofHours(1))),
        request);

    List<ResponseRequest> due =
        requests.findRenotifyDue(
            RequestStatus.WAITING,
            now,
            Instant.EPOCH,
            new UUID(0, 0),
            NoticeKind.RENOTIFY,
            RenotifyStateStore.EVIDENCE_KINDS,
            Limit.of(10_000));
    assertThat(due).extracting(ResponseRequest::getId).doesNotContain(request);
    worker.runOnce(50);
    assertThat(renotices(request)).hasSize(1);
  }

  @Test
  void 두_번_돌려도_재통지는_요청당_한_번이다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);
    worker.runOnce(50);

    assertThat(renotices(request)).hasSize(1);
    assertThat(renotifyActions(room)).hasSize(1);
  }

  @Test
  void 재통지가_끝난_상태가_되어도_같은_요청에는_다시_만들지_않는다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    jdbc.update(
        "UPDATE notice SET status = 'CANCELLED' WHERE response_request_id = ? AND kind = 'RENOTIFY'",
        request);
    // 같은 요청이 다시 기다림이 되어 기한을 다시 셌다(제안 바꾸기 등, 03 1-4)
    jdbc.update(
        "UPDATE response_request SET renotify_due_at = ? WHERE id = ?",
        Timestamp.from(now.minusSeconds(1)),
        request);

    worker.runOnce(50);

    assertThat(renotices(request)).hasSize(1);
  }

  @Test
  void 같은_요청을_두_작업자가_동시에_돌려도_재통지는_한_번이다() throws Exception {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        runs.add(
            pool.submit(() -> {
              go.await();
              return worker.runOnce(50);
            }));
      }
      go.countDown();
      for (Future<Integer> run : runs) {
        run.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(renotices(request)).hasSize(1);
    assertThat(renotifyActions(room)).hasSize(1);
  }

  @Test
  void 닫힌_뒤_새로_연_요청은_새_기다림이라_따로_재통지한다() {
    UUID link = inviteLink(room, landlord, null);
    UUID first = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(first, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    jdbc.update(
        "UPDATE response_request SET status = 'CLOSED', closed_reason = 'ROOM_ENDED', closed_at = ?"
            + " WHERE id = ?",
        Timestamp.from(now),
        first);
    UUID second = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(1));
    reachedNotice(second, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(first)).hasSize(1);
    assertThat(renotices(second)).hasSize(1);
  }

  @Test
  void 건너뛴_요청이_앞에_있어도_뒤_요청은_같은_실행에서_처리한다() {
    // 한 번에 1개씩 읽어도 앞의 건너뛴 요청(옛 번호 링크)이 다음 쪽을 막지 않는다
    UUID oldLink = inviteLink(room, landlord, "REINVITED");
    UUID skipped = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(120));
    reachedNotice(skipped, landlord, oldLink, "REQUEST", "DELIVERED");
    UUID otherRoom = room("LIVING");
    UUID otherLandlord = seat(otherRoom, "LANDLORD", "INVITED");
    UUID link = inviteLink(otherRoom, otherLandlord, null);
    UUID next = startedRequest(otherRoom, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(next, otherLandlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(1);

    assertThat(renotices(skipped)).isEmpty();
    assertThat(renotices(next)).hasSize(1);
  }

  @Test
  void 기한이_같은_요청이_여럿이어도_한_쪽씩_읽어_모두_처리한다() {
    Instant due = now.minusSeconds(60);
    List<UUID> requests = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID r = room("LIVING");
      UUID seat = seat(r, "LANDLORD", "INVITED");
      UUID request = startedRequest(r, "TENANT", "WAITING", due);
      reachedNotice(request, seat, inviteLink(r, seat, null), "REQUEST", "DELIVERED");
      requests.add(request);
    }

    worker.runOnce(1);

    requests.forEach(request -> assertThat(renotices(request)).hasSize(1));
  }

  @Test
  void 요청이_걸린_초대_문자가_닿았으면_그_초대_링크로_재통지한다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "INVITE", "VIEWED");

    worker.runOnce(50);

    assertThat(renotices(request))
        .singleElement()
        .satisfies(n -> assertThat(n).containsEntry("link_id", link));
  }

  // ---- 호실·자리·링크 ----

  @Test
  void 정산이_끝난_호실은_재통지가_없다() {
    jdbc.update(
        "UPDATE room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
    assertThat(renotifyActions(room)).isEmpty();
  }

  @Test
  void 거주_종료_뒤에는_정산을_마무리하는_종류만_재통지한다() {
    // 03 K "거주 종료 뒤": 고친 내용 확인은 나가고, 거주 종료 요청은 나가지 않는다(RequestOpening)
    jdbc.update(
        "UPDATE room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?", room);
    UUID tenant = seat(room, "TENANT", "INVITED");
    UUID landlordLink = inviteLink(room, landlord, null);
    UUID tenantLink = inviteLink(room, tenant, null);
    UUID correction = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(correction, landlord, landlordLink, "REQUEST", "DELIVERED");
    UUID endRequest =
        request(
            room,
            "END_REQUEST",
            "LANDLORD",
            "WAITING",
            now.minus(Duration.ofHours(49)),
            now.minusSeconds(60));
    reachedNotice(endRequest, tenant, tenantLink, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(correction)).hasSize(1);
    assertThat(renotices(endRequest)).isEmpty();
  }

  @Test
  void 합류_전_기록은_재통지가_없다() {
    UUID into = room("LIVING");
    jdbc.update(
        "UPDATE room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        into,
        room);
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "WITHDRAWN"})
  void 답할_자리가_거절_탈퇴면_재통지가_없다(String status) {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    jdbc.update("UPDATE party SET status = ? WHERE id = ?", status, landlord);

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  @Test
  void 답할_자리가_공유를_그만뒀으면_재통지가_없다() {
    UUID member = member();
    jdbc.update(
        "UPDATE party SET status = 'SHARING_STOPPED', member_id = ?, sharing_stopped_at = ?"
            + " WHERE id = ?",
        member,
        Timestamp.from(now.minusSeconds(30)),
        landlord);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, null, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  @Test
  void 번호를_고쳐_무효가_된_옛_링크로는_재통지하지_않는다() {
    // #370 T-2: 옛 번호로 간 문자의 링크다. 새 링크 통지가 닿으면 그 링크로 간다
    UUID oldLink = inviteLink(room, landlord, "REINVITED");
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, oldLink, "REQUEST", "DELIVERED");

    worker.runOnce(50);
    assertThat(renotices(request)).isEmpty();

    UUID newLink = inviteLink(room, landlord, null);
    reachedNotice(request, landlord, newLink, "REQUEST", "DELIVERED");
    worker.runOnce(50);

    assertThat(renotices(request))
        .singleElement()
        .satisfies(n -> assertThat(n).containsEntry("link_id", newLink));
  }

  @Test
  void 다른_자리에_닿은_통지는_재통지의_근거가_아니다() {
    UUID tenant = seat(room, "TENANT", "INVITED");
    UUID link = inviteLink(room, tenant, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, tenant, link, "REQUEST", "DELIVERED");

    worker.runOnce(50);

    assertThat(renotices(request)).isEmpty();
  }

  @Test
  void 링크가_없는_통지가_근거면_재통지도_링크_없이_간다() {
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, null, "REQUEST", "VIEWED");

    worker.runOnce(50);

    assertThat(renotices(request))
        .singleElement()
        .satisfies(n -> assertThat(n).containsEntry("link_id", null));
  }

  // ---- 재통지가 닿은 뒤 ----

  @Test
  void 재통지가_닿으면_닿은_시각부터_미확인_기한이_생기고_같은_재통지가_또_닿아도_그대로다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    UUID renotice = (UUID) renotices(request).getFirst().get("id");
    Instant reachedAt = now.plusSeconds(5);

    reach(renotice, reachedAt);
    reach(renotice, reachedAt.plusSeconds(600));

    Map<String, Object> row = requestRow(request);
    assertThat(row).containsEntry("status", "WAITING");
    assertThat(((Timestamp) row.get("unconfirmed_due_at")).toInstant())
        .isEqualTo(reachedAt.plus(UNCONFIRMED));
  }

  @Test
  void 옛_번호_링크의_재통지가_닿은_것은_미확인_기한이_아니다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    UUID renotice = (UUID) renotices(request).getFirst().get("id");
    jdbc.update(
        "UPDATE link SET invalidated_at = ?, invalidated_reason = 'REINVITED' WHERE id = ?",
        Timestamp.from(now),
        link);

    reach(renotice, now.plusSeconds(5));

    assertThat(requestRow(request)).containsEntry("unconfirmed_due_at", null);
  }

  @Test
  void 재통지가_닿을_때_요청이_이미_답했으면_미확인_기한이_없다() {
    UUID link = inviteLink(room, landlord, null);
    UUID request = startedRequest(room, "TENANT", "WAITING", now.minusSeconds(60));
    reachedNotice(request, landlord, link, "REQUEST", "DELIVERED");
    worker.runOnce(50);
    UUID renotice = (UUID) renotices(request).getFirst().get("id");
    jdbc.update("UPDATE response_request SET status = 'ANSWERED' WHERE id = ?", request);

    reach(renotice, now.plusSeconds(5));

    assertThat(requestRow(request)).containsEntry("unconfirmed_due_at", null);
  }

  // ---- 도우미 ----

  /** 전달 결과 작업자와 같은 순서로(호실 → 통지 → 요청) 재통지가 닿은 것을 적는다. */
  private void reach(UUID noticeId, Instant at) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          waitStart.lockRoomOf(noticeId);
          Notice notice = notices.findForUpdate(noticeId).orElseThrow();
          waitStart.reached(notice, at);
        });
  }

  private UUID room(String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, ?, '재통지 호실', ?, 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        status);
    return id;
  }

  private UUID seat(UUID roomId, String side, String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        side,
        status);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  /** 이 자리의 초대 링크. {@code reason}이 null이면 유효한 링크. */
  private UUID inviteLink(UUID roomId, UUID party, String reason) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO link (id, room_record_id, kind, party_id, token_hash, issued_by_member_id,
          invalidated_at, invalidated_reason, created_at, version)
        VALUES (?, ?, 'INVITE', ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        roomId,
        party,
        "hash-" + id,
        member(),
        reason == null ? null : Timestamp.from(now.minusSeconds(10)),
        reason,
        Timestamp.from(now.minus(Duration.ofDays(3))));
    return id;
  }

  /** 첫 통지가 49시간 전에 닿아 기다림이 시작된 요청(입주일 고친 내용 확인처럼 대상 칸이 빈 호실 기록 요청). */
  private UUID startedRequest(UUID roomId, String requestedBySide, String status, Instant due) {
    return request(
        roomId, requestedBySide, status, due == null ? null : due.minus(Duration.ofHours(48)), due);
  }

  private UUID request(
      UUID roomId, String requestedBySide, String status, Instant firstNotifiedAt, Instant due) {
    return request(roomId, "CORRECTION_CONFIRM", requestedBySide, status, firstNotifiedAt, due);
  }

  private UUID request(
      UUID roomId,
      String kind,
      String requestedBySide,
      String status,
      Instant firstNotifiedAt,
      Instant due) {
    UUID id = UUID.randomUUID();
    boolean closed = status.equals("CLOSED");
    jdbc.update(
        """
        INSERT INTO response_request (id, room_record_id, kind, requested_by_side, status,
          first_notified_at, renotify_due_at, closed_reason, closed_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        roomId,
        kind,
        requestedBySide,
        status,
        firstNotifiedAt == null ? null : Timestamp.from(firstNotifiedAt),
        due == null ? null : Timestamp.from(due),
        closed ? "ROOM_ENDED" : null,
        closed ? Timestamp.from(now) : null,
        Timestamp.from(now.minus(Duration.ofDays(3))));
    return id;
  }

  /** 요청에 걸린 문자 통지. 시각 칸은 상태에 맞춰 채운다(V1 CHECK). */
  private UUID reachedNotice(UUID request, UUID party, UUID link, String kind, String status) {
    UUID id = UUID.randomUUID();
    Instant sentAt = now.minus(Duration.ofHours(50));
    jdbc.update(
        """
        INSERT INTO notice (id, room_record_id, response_request_id, link_id, recipient_party_id,
          kind, channel, status, sent_at, delivered_at, viewed_at, provider_message_id,
          batch_lead_id, delivery_check_due_at, created_at, version)
        SELECT ?, r.room_record_id, r.id, ?, ?, ?, 'SMS', ?, ?, ?, ?, ?, ?, ?, ?, 0
        FROM response_request r WHERE r.id = ?
        """,
        id,
        link,
        party,
        kind,
        status,
        Timestamp.from(sentAt),
        status.equals("DELIVERED") ? Timestamp.from(sentAt.plusSeconds(60)) : null,
        status.equals("VIEWED") ? Timestamp.from(sentAt.plusSeconds(60)) : null,
        "fake-" + id,
        id,
        Timestamp.from(sentAt.plus(Duration.ofDays(3))),
        Timestamp.from(sentAt.minusSeconds(1)),
        request);
    return id;
  }

  private List<Map<String, Object>> renotices(UUID request) {
    return jdbc.queryForList(
        "SELECT id, status, channel, recipient_party_id, link_id FROM notice"
            + " WHERE response_request_id = ? AND kind = 'RENOTIFY'",
        request);
  }

  private int outboxRows(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox WHERE dedupe_key = ?", Integer.class, "sms:" + noticeId);
  }

  private List<Map<String, Object>> renotifyActions(UUID roomId) {
    return jdbc.queryForList(
        "SELECT actor_key, actor_side, idempotency_key, response_request_id FROM record.action"
            + " WHERE room_record_id = ? AND type = 'RENOTIFY'",
        roomId);
  }

  private Map<String, Object> requestRow(UUID request) {
    return jdbc.queryForMap(
        "SELECT status, renotify_due_at, unconfirmed_due_at FROM response_request WHERE id = ?",
        request);
  }
}
