package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.auth.dto.WithdrawRequest;
import com.chilsuitjo.back.auth.service.WithdrawService;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.notification.dto.SmsNoticeCommand;
import com.chilsuitjo.back.notification.repository.OutboxRepository;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.service.InviteLinks;
import com.chilsuitjo.back.notification.service.NoticeOutbox;
import com.chilsuitjo.back.room.service.RoomWithdrawalService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴가 호실마다 D 계약 셋(#435)을 부르는지 실제 DB 구현으로 본다: 열린 요청 닫기 → 발급 링크 무효 → 보내지 않은 문자 취소, 같은 탈퇴 시각, 하나가 실패하면
 * 모두 되돌림(#424 합의). 경합은 #424 D 7237256118의 넷(연결은 {@code WithdrawRaceTest})을 본다. 호실 잠금만으로 다 풀린다고 보지
 * 않고, 작업자처럼 호실을 잡지 않는 길은 따로 본다.
 *
 * <p>요청은 실제 DB 구현({@code test.notification.wait-service=jpa})으로 닫아 롤백까지 본다. 문자 작업자 예약 실행은 끄고 가져가기
 * 쿼리를 직접 부른다({@link SmsCancellationServiceTest}와 같은 설정).
 */
@Import({TestcontainersConfiguration.class, OutboxWorkerTest.RecipientsConfig.class})
@SpringBootTest(
    properties = {"test.notification.wait-service=jpa", "app.outbox.worker.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawCloseOutTest {

  private static final String PASSWORD = "homestamp123";

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  private static final Instant EARLIER = Instant.parse("2026-11-20T01:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private WithdrawService withdrawals;
  @Autowired private NoticeOutbox noticeOutbox;
  @Autowired private OutboxRepository outbox;
  @Autowired private InviteLinks inviteLinks;
  @Autowired private TransactionTemplate transaction;
  @MockitoSpyBean private RoomWithdrawalService rooms;

  private final List<UUID> createdRooms = new ArrayList<>();
  private final List<UUID> createdMembers = new ArrayList<>();
  private UUID me;
  private UUID other;
  private UUID room;
  private UUID mySeat;
  private UUID otherSeat;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    me = member();
    other = member();
    room = room();
    mySeat = seat(room, "TENANT", me, "CONNECTED");
    otherSeat = seat(room, "LANDLORD", other, "CONNECTED");
  }

  @AfterEach
  void cleanUp() {
    for (UUID r : createdRooms) {
      jdbc.update(
          """
          UPDATE state.outbox SET status = 'DONE'
          WHERE status IN ('PENDING', 'SENDING')
            AND dedupe_key IN (SELECT 'sms:' || id FROM state.notice WHERE room_record_id = ?)
          """,
          r);
    }
    for (UUID m : createdMembers) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", m);
    }
  }

  @Test
  void 호실마다_같은_탈퇴_시각으로_열린_요청을_닫고_발급_링크를_무효로_하고_보내지_않은_문자를_취소한다() {
    UUID fromMe = request(room, "CORRECTION_CONFIRM", "TENANT", "WAITING");
    UUID fromOther = request(room, "PROGRESS_CHECK", "LANDLORD", "UNCONFIRMED");
    UUID answered = request(room, "DEFECT_CONFIRM", "LANDLORD", "ANSWERED");
    UUID mySettlementLink = link(room, otherSeat, me, "SETTLEMENT_INVITE");
    UUID othersLink = link(room, mySeat, other, "INVITE");
    UUID othersPending = enqueue(room, mySeat, othersLink);
    // 상대가 연결된 적 없는 호실(KEEP-4a): 내가 보낸 초대 링크와 그 문자
    UUID solo = room();
    seat(solo, "TENANT", me, "CONNECTED");
    UUID invitedSeat = seat(solo, "LANDLORD", null, "INVITED");
    UUID myInvite = link(solo, invitedSeat, me, "INVITE");
    UUID myPending = enqueue(solo, invitedSeat, myInvite);

    withdrawals.withdraw(me, new WithdrawRequest(PASSWORD));

    Instant withdrawnAt =
        jdbc.queryForObject(
                "SELECT withdrawn_at FROM state.member WHERE id = ?", Timestamp.class, me)
            .toInstant();
    assertThat(requestRow(fromMe)).isEqualTo("CLOSED COUNTERPART_WITHDRAWN " + withdrawnAt);
    assertThat(requestRow(fromOther)).isEqualTo("CLOSED COUNTERPART_WITHDRAWN " + withdrawnAt);
    assertThat(requestRow(answered)).isEqualTo("ANSWERED null null");
    assertThat(linkRow(mySettlementLink)).isEqualTo("ISSUER_WITHDRAWN " + withdrawnAt);
    assertThat(linkRow(myInvite)).isEqualTo("ISSUER_WITHDRAWN " + withdrawnAt);
    assertThat(linkRow(othersLink)).isEqualTo("null null");
    assertThat(sms(myPending)).isEqualTo("CANCELLED CANCELLED");
    assertThat(sms(othersPending)).isEqualTo("REQUESTED PENDING");
  }

  @Test
  void 문자_취소가_실패하면_요청_닫기_링크_무효_자리_회원_토큰까지_모두_되돌아간다() {
    UUID waiting = request(room, "CORRECTION_CONFIRM", "LANDLORD", "WAITING");
    UUID myLink = link(room, otherSeat, me, "INVITE");
    UUID broken = enqueue(room, otherSeat, myLink);
    // 지금 코드로 생길 수 없는 조합(줄은 PENDING인데 통지는 보낸 뒤). 취소가 실패를 숨기지 않고 던진다(#435 WithdrawalSupportTest와 같은
    // 방법)
    jdbc.update(
        "UPDATE state.notice SET status = 'SENT', sent_at = ?, delivery_check_due_at = ?"
            + " WHERE id = ?",
        Timestamp.from(EARLIER),
        Timestamp.from(EARLIER.plusSeconds(86_400)),
        broken);
    jdbc.update(
        "INSERT INTO state.refresh_token (id, member_id, family_id, token_hash, expires_at,"
            + " created_at, version) VALUES (?, ?, gen_random_uuid(), ?, ?, CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        me,
        "rollback-" + UUID.randomUUID(),
        Timestamp.from(Instant.now().plusSeconds(3600)));

    assertThatThrownBy(() -> withdrawals.withdraw(me, new WithdrawRequest(PASSWORD)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT status || ' ' || (email IS NOT NULL) FROM state.member WHERE id = ?",
                String.class,
                me))
        .isEqualTo("ACTIVE true");
    assertThat(seatStatus(mySeat)).isEqualTo("CONNECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.refresh_token WHERE member_id = ? AND revoked_at IS NULL",
                Integer.class,
                me))
        .isEqualTo(1);
    assertThat(requestRow(waiting)).isEqualTo("WAITING null null");
    assertThat(linkRow(myLink)).isEqualTo("null null");
    assertThat(sms(broken)).isEqualTo("SENT PENDING");
  }

  @Test
  void 상대의_응답이_호실을_먼저_잡으면_탈퇴는_기다리고_답한_요청은_그대로_둔다() throws Exception {
    UUID answering = request(room, "CORRECTION_CONFIRM", "TENANT", "WAITING");
    UUID untouched = request(room, "PROGRESS_CHECK", "TENANT", "WAITING");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // 상대의 응답 행동: pipeline처럼 호실을 잠그고 요청을 답함으로 바꾼 채 커밋 전
    CompletableFuture<Void> answer =
        CompletableFuture.runAsync(() ->
            transaction.executeWithoutResult(s -> {
              jdbc.queryForObject(
                  "SELECT id FROM state.room_record WHERE id = ? FOR UPDATE", UUID.class, room);
              jdbc.update(
                  "UPDATE state.response_request SET status = 'ANSWERED' WHERE id = ?", answering);
              locked.countDown();
              await(release);
            }));
    await(locked);

    CompletableFuture<Void> withdrawing =
        CompletableFuture.runAsync(() -> withdrawals.withdraw(me, new WithdrawRequest(PASSWORD)));
    Thread.sleep(300);
    assertThat(withdrawing).isNotDone();
    release.countDown();
    answer.get(10, TimeUnit.SECONDS);
    withdrawing.get(10, TimeUnit.SECONDS);

    assertThat(requestRow(answering)).isEqualTo("ANSWERED null null");
    assertThat(requestRow(untouched)).startsWith("CLOSED COUNTERPART_WITHDRAWN ");
  }

  @Test
  void 탈퇴가_호실을_잡은_동안_온_상대의_응답은_기다렸다가_닫힌_요청을_본다() throws Exception {
    UUID waiting = request(room, "CORRECTION_CONFIRM", "TENANT", "WAITING");
    Hold hold = holdAfterLeave();
    CompletableFuture<Void> withdrawing =
        CompletableFuture.runAsync(() -> withdrawals.withdraw(me, new WithdrawRequest(PASSWORD)));
    await(hold.locked);

    // 상대의 응답 행동: pipeline처럼 호실을 먼저 잠근 뒤 요청 상태를 본다
    CompletableFuture<String> answer =
        CompletableFuture.supplyAsync(() ->
            transaction.execute(s -> {
              jdbc.queryForObject(
                  "SELECT id FROM state.room_record WHERE id = ? FOR UPDATE", UUID.class, room);
              return requestRow(waiting);
            }));
    Thread.sleep(300);
    assertThat(answer).isNotDone();
    hold.release.countDown();
    withdrawing.get(10, TimeUnit.SECONDS);

    assertThat(answer.get(10, TimeUnit.SECONDS)).startsWith("CLOSED COUNTERPART_WITHDRAWN ");
  }

  @Test
  void 탈퇴가_호실을_잡은_동안_연_공개_링크는_기다렸다가_LINK_INVALID() throws Exception {
    UUID invitedRoom = room();
    seat(invitedRoom, "TENANT", me, "CONNECTED");
    UUID invitedSeat = seat(invitedRoom, "LANDLORD", null, "INVITED");
    String token = LinkToken.newToken();
    UUID linkId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'INVITE', ?, CURRENT_TIMESTAMP, 0)",
        linkId,
        invitedRoom,
        invitedSeat,
        me,
        LinkToken.hash(token));
    Hold hold = holdAfterLeave();
    CompletableFuture<Void> withdrawing =
        CompletableFuture.runAsync(() -> withdrawals.withdraw(me, new WithdrawRequest(PASSWORD)));
    await(hold.locked);

    CompletableFuture<ResultActions> viewing =
        CompletableFuture.supplyAsync(() -> {
          try {
            return view(token);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    Thread.sleep(300);
    assertThat(viewing).isNotDone();
    hold.release.countDown();
    withdrawing.get(10, TimeUnit.SECONDS);

    viewing
        .get(10, TimeUnit.SECONDS)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCode.LINK_INVALID.name()));
    assertThat(linkRow(linkId)).startsWith("ISSUER_WITHDRAWN ");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE room_record_id = ? AND type = 'LINK_VIEW'",
                Integer.class,
                invitedRoom))
        .isZero();
  }

  @Test
  void 탈퇴가_문자_줄을_잡은_동안_작업자는_그_줄을_건너뛰고_커밋_뒤에는_취소돼_있다() throws Exception {
    UUID myLink = link(room, otherSeat, me, "INVITE");
    UUID pending = enqueue(room, otherSeat, myLink);
    UUID outboxId = outboxIdOf(pending);
    assertThat(claimable()).contains(outboxId); // 탈퇴 전에는 작업자가 가져갈 줄이다
    Hold hold = holdAfterLeave();
    CompletableFuture<Void> withdrawing =
        CompletableFuture.runAsync(() -> withdrawals.withdraw(me, new WithdrawRequest(PASSWORD)));
    await(hold.locked);

    // 작업자는 호실을 잡지 않고 문자 줄부터 가져간다(SKIP LOCKED). 탈퇴가 취소한 줄은 잠겨 있어 건너뛴다
    assertThat(CompletableFuture.supplyAsync(this::claimable).get(10, TimeUnit.SECONDS))
        .doesNotContain(outboxId);
    hold.release.countDown();
    withdrawing.get(10, TimeUnit.SECONDS);

    assertThat(sms(pending)).isEqualTo("CANCELLED CANCELLED");
    assertThat(claimable()).doesNotContain(outboxId);
  }

  @Test
  void 작업자가_이미_가져간_문자는_그대로_두고_보내기_전_확인에서_링크_무효를_본다() {
    UUID myLink = link(room, otherSeat, me, "INVITE");
    UUID sending = enqueue(room, otherSeat, myLink);
    jdbc.update(
        "UPDATE state.outbox SET status = 'SENDING' WHERE dedupe_key = ?", "sms:" + sending);

    withdrawals.withdraw(me, new WithdrawRequest(PASSWORD));

    assertThat(sms(sending)).isEqualTo("REQUESTED SENDING");
    // 작업자(OutboxWorker)는 업체를 부르기 전에 이 값을 보고 스스로 취소한다
    assertThat(inviteLinks.isInvalidatedInvite(myLink)).isTrue();
  }

  // ---- 준비물 ----

  private record Hold(CountDownLatch locked, CountDownLatch release) {}

  /** 탈퇴 트랜잭션이 자리를 바꾸고 D 셋을 부른 뒤(호실·요청·링크·문자 줄을 잠근 채) 커밋 전에 멈추게 한다. */
  private Hold holdAfterLeave() {
    Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              hold.locked.countDown();
              await(hold.release);
              return result;
            })
        .when(AopTestUtils.<RoomWithdrawalService>getTargetObject(rooms))
        .leave(any(), any(), any());
    return hold;
  }

  /** 작업자가 지금 가져갈 수 있는 문자 줄(가져가지는 않고 되돌림). */
  private List<UUID> claimable() {
    return transaction.execute(s -> {
      s.setRollbackOnly();
      return outbox.lockDueSms(Instant.now().plusSeconds(86_400), 10_000).stream()
          .map(row -> row.getId())
          .toList();
    });
  }

  private ResultActions view(String token) throws Exception {
    return mvc.perform(
        post("/api/public/links/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("기다림이 끝나지 않았어요");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, phone,"
            + " phone_verified_at, status, created_at, version)"
            + " VALUES (?, ?, ?, '정서윤', ?, ?, 'ACTIVE', ?, 0)",
        id,
        "closeout-" + id + "@example.com",
        passwordEncoder.encode(PASSWORD),
        "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000)),
        now,
        now);
    createdMembers.add(id);
    return id;
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
    createdRooms.add(id);
    return id;
  }

  private UUID seat(UUID roomId, String side, UUID memberId, String seatStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, connected_at, created_at,
           version)
        VALUES (?, ?, ?, ?, ?, ?,
          CASE WHEN CAST(? AS varchar) = 'CONNECTED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        side,
        memberId,
        "INVITED".equals(seatStatus) ? "01055556666" : null,
        seatStatus,
        seatStatus);
    return id;
  }

  private UUID request(UUID roomId, String kind, String side, String requestStatus) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.response_request (id, room_record_id, kind, requested_by_side, status,
          created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        roomId,
        kind,
        side,
        requestStatus,
        Timestamp.from(EARLIER));
    return id;
  }

  private UUID link(UUID roomId, UUID partyId, UUID issuer, String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind, token_hash,
          created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        partyId,
        issuer,
        kind,
        id.toString());
    return id;
  }

  private UUID enqueue(UUID roomId, UUID partyId, UUID linkId) {
    return transaction.execute(s ->
        noticeOutbox.enqueueSms(
            new SmsNoticeCommand(roomId, partyId, NoticeKind.INVITE, null, linkId)));
  }

  private UUID outboxIdOf(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT id FROM state.outbox WHERE dedupe_key = ?", UUID.class, "sms:" + noticeId);
  }

  private String seatStatus(UUID partyId) {
    return jdbc.queryForObject(
        "SELECT status FROM state.party WHERE id = ?", String.class, partyId);
  }

  private String requestRow(UUID id) {
    return jdbc.queryForObject(
        "SELECT status, closed_reason, closed_at FROM state.response_request WHERE id = ?",
        (rs, n) ->
            rs.getString(1)
                + " "
                + rs.getString(2)
                + " "
                + (rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
        id);
  }

  private String linkRow(UUID id) {
    return jdbc.queryForObject(
        "SELECT invalidated_reason, invalidated_at FROM state.link WHERE id = ?",
        (rs, n) ->
            rs.getString(1)
                + " "
                + (rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()),
        id);
  }

  private String sms(UUID noticeId) {
    return jdbc.queryForObject(
        "SELECT n.status, o.status FROM state.notice n"
            + " JOIN state.outbox o ON o.dedupe_key = 'sms:' || n.id WHERE n.id = ?",
        (rs, n) -> rs.getString(1) + " " + rs.getString(2),
        noticeId);
  }
}
