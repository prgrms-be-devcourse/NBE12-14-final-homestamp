package com.chilsuitjo.back.auth.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.auth.dto.WithdrawRequest;
import com.chilsuitjo.back.global.error.ErrorCode;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * 탈퇴와 같은 회원의 연결이 겹칠 때(#424 D 2번). 잠금 순서는 둘 다 호실 → 회원 행이다.
 *
 * <ul>
 *   <li>탈퇴가 호실을 잠근 뒤 회원 행을 잡기 전에 연결이 커밋되면, 탈퇴는 새 자리를 보고 되돌린 뒤 다시 한다({@code WithdrawService})
 *   <li>탈퇴가 회원 행을 잡은 동안 온 연결은 회원 행 공유 잠금에서 기다렸다가 {@code LOGIN_REQUIRED}로 거절된다({@code
 *       MemberQueryService.holdActive})
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawRaceTest {

  private static final String PASSWORD = "homestamp123";

  /** {@code InvitationApiTest}와 같은 법정동(시·도 97). */
  private static final String REGION = "9799999901";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private WithdrawService withdrawals;
  @Autowired private TransactionTemplate transaction;
  @MockitoSpyBean private RoomWithdrawalService rooms;

  private final List<UUID> created = new ArrayList<>();
  private String phone;
  private UUID me;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    phone = newPhone();
    me = member(phone);
  }

  @AfterEach
  void removeTokens() {
    for (UUID id : created) {
      jdbc.update("DELETE FROM state.refresh_token WHERE member_id = ?", id);
    }
  }

  @Test
  void 호실을_잠근_뒤_연결이_먼저_커밋되면_되돌리고_다시_해서_새_자리까지_탈퇴함이_된다() {
    UUID first = room();
    seat(first, "TENANT", me, "CONNECTED");
    UUID late = room();
    seat(late, "TENANT", member(newPhone()), "CONNECTED");
    UUID lateSeat = seat(late, "LANDLORD", null, "INVITED");
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              Object locked = invocation.callRealMethod();
              if (calls.incrementAndGet() == 1) {
                // 호실을 잠근 뒤 회원 행을 잡기 전에 다른 트랜잭션의 연결이 커밋된 모양
                CompletableFuture.runAsync(() ->
                        jdbc.update(
                            "UPDATE state.party SET status = 'CONNECTED', member_id = ?,"
                                + " connected_at = CURRENT_TIMESTAMP WHERE id = ?",
                            me,
                            lateSeat))
                    .join();
              }
              return locked;
            })
        .when(AopTestUtils.<RoomWithdrawalService>getTargetObject(rooms))
        .lockRoomsOf(any());

    withdrawals.withdraw(me, new WithdrawRequest(PASSWORD));

    assertThat(calls).hasValue(2);
    assertThat(seatStatus(first, "TENANT")).isEqualTo("WITHDRAWN");
    assertThat(seatStatus(late, "LANDLORD")).isEqualTo("WITHDRAWN");
    assertThat(memberStatus(me)).isEqualTo("WITHDRAWN");
  }

  @Test
  void 탈퇴가_회원_행을_잡은_동안_온_연결은_기다렸다가_LOGIN_REQUIRED() throws Exception {
    UUID room = room();
    seat(room, "TENANT", member(newPhone()), "CONNECTED");
    UUID invited = seat(room, "LANDLORD", null, "INVITED");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // 탈퇴 트랜잭션이 회원 행을 쓰기 잠금하고 가린 채 커밋 전인 모양
    CompletableFuture<Void> withdrawing =
        CompletableFuture.runAsync(() ->
            transaction.executeWithoutResult(status -> {
              jdbc.queryForObject(
                  "SELECT id FROM state.member WHERE id = ? FOR UPDATE", UUID.class, me);
              jdbc.update(
                  "UPDATE state.member SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,"
                      + " email = NULL, password_hash = NULL, display_name = NULL"
                      + " WHERE id = ?",
                  me);
              locked.countDown();
              await(release);
            }));
    await(locked);

    CompletableFuture<ResultActions> connecting =
        CompletableFuture.supplyAsync(() -> {
          try {
            return connect(invited);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    Thread.sleep(300);
    assertThat(connecting).isNotDone();
    release.countDown();
    withdrawing.get(10, TimeUnit.SECONDS);

    connecting
        .get(10, TimeUnit.SECONDS)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_REQUIRED.name()));
    assertThat(seatStatus(room, "LANDLORD")).isEqualTo("INVITED");
  }

  // ---- 준비물 ----

  private ResultActions connect(UUID party) throws Exception {
    return mvc.perform(
        post("/api/invitations/{id}/connect", party)
            .with(loginAs(me))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"consentVersion\":\"v1\"}"));
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

  private UUID member(String memberPhone) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO state.member (id, email, password_hash, display_name, phone,"
            + " phone_verified_at, status, created_at, version)"
            + " VALUES (?, ?, ?, '정서윤', ?, ?, 'ACTIVE', ?, 0)",
        id,
        "race-" + id + "@example.com",
        passwordEncoder.encode(PASSWORD),
        memberPhone,
        now,
        now);
    created.add(id);
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
    return id;
  }

  /** 초대됨 자리면 {@link #phone}으로 초대한 자리다. */
  private UUID seat(UUID room, String side, UUID memberId, String seatStatus) {
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
        room,
        side,
        memberId,
        "INVITED".equals(seatStatus) ? phone : null,
        seatStatus,
        seatStatus);
    return id;
  }

  private String seatStatus(UUID room, String side) {
    return jdbc.queryForObject(
        "SELECT status FROM state.party WHERE room_record_id = ? AND side = ?",
        String.class,
        room,
        side);
  }

  private String memberStatus(UUID memberId) {
    return jdbc.queryForObject(
        "SELECT status FROM state.member WHERE id = ?", String.class, memberId);
  }

  private static String newPhone() {
    return "010%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
