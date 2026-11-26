package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.room.entity.Party;
import com.chilsuitjo.back.room.repository.PartyRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 06 4-3 {@code GET /api/invitations}(받은 초대, ROOM-5, S-08). 보안 설정까지 띄워 실제 액세스 JWT로 부른다.
 *
 * <p>번호는 테스트마다 새로 만들어 다른 테스트의 초대와 섞이지 않게 한다. 호실 ID는 만든 순서가 보이게 시각을 올려 가며 UUID v7로 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationApiTest {

  /** 다른 테스트가 쓰지 않는 시·도(97). 같은 시·도 코드에 이름이 둘이면 시·도 목록이 겹친다(RegionApiTest). */
  private static final String REGION = "9799999901";

  private static final AtomicLong CLOCK = new AtomicLong(System.currentTimeMillis());

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PartyRepository parties;

  private String phone;
  private UUID me;
  private UUID inviter;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES (?, '초대시', '초대구', '초대동')"
            + " ON CONFLICT DO NOTHING",
        REGION);
    phone = newPhone();
    me = member(null, phone);
    inviter = member("정서윤", newPhone());
  }

  @Test
  void 내_인증_번호로_초대된_자리가_보인다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", inviter, "CONNECTED", null);
    UUID party = seat(room, "LANDLORD", null, "INVITED", phone);

    list(me)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].partyId").value(party.toString()))
        .andExpect(jsonPath("$[0].side").value("LANDLORD"))
        .andExpect(jsonPath("$[0].region.code").value(REGION))
        .andExpect(jsonPath("$[0].region.sido").value("초대시"))
        .andExpect(jsonPath("$[0].region.sigungu").value("초대구"))
        .andExpect(jsonPath("$[0].region.dong").value("초대동"))
        .andExpect(jsonPath("$[0].inviterName").value("정*윤"))
        // 연결 전이라 호실 기록·별칭·번호는 주지 않는다
        .andExpect(jsonPath("$[0].roomRecordId").doesNotExist())
        .andExpect(jsonPath("$[0].alias").doesNotExist())
        .andExpect(jsonPath("$[0].invitePhone").doesNotExist());
  }

  @Test
  void 초대하면_저장한_초대_번호로_찾는다() throws Exception {
    UUID room = room("LIVING", "LANDLORD");
    seat(room, "LANDLORD", inviter, "CONNECTED", null);
    Party empty = parties.saveAndFlush(Party.empty(room, PartySide.TENANT, Instant.now()));
    Party saved = parties.findById(empty.getId()).orElseThrow();
    saved.invite(phone);
    parties.saveAndFlush(saved);

    list(me)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].partyId").value(empty.getId().toString()))
        .andExpect(jsonPath("$[0].side").value("TENANT"));
  }

  @Test
  void 새로_만든_호실의_초대부터_보인다() throws Exception {
    UUID older = invitedRoom();
    UUID newer = invitedRoom();

    list(me)
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].partyId").value(newer.toString()))
        .andExpect(jsonPath("$[1].partyId").value(older.toString()));
  }

  @Test
  void 다른_번호나_초대됨이_아닌_자리는_보이지_않는다() throws Exception {
    UUID other = room("LIVING", "TENANT");
    seat(other, "TENANT", inviter, "CONNECTED", null);
    seat(other, "LANDLORD", null, "INVITED", newPhone());
    UUID rejected = room("LIVING", "TENANT");
    seat(rejected, "TENANT", inviter, "CONNECTED", null);
    seat(rejected, "LANDLORD", null, "REJECTED", phone);

    list(me).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void 거주_종료_전에_받은_초대는_정산_완료_전까지_보인다() throws Exception {
    // 임시 정책(#163 3-1, 팀 최종 합의 전): 거주 종료 호실은 보이고 정산 완료 호실은 빠진다. 연결 규칙과 같은 기준
    UUID ended = room("ENDED", "TENANT");
    seat(ended, "TENANT", inviter, "CONNECTED", null);
    UUID endedSeat = seat(ended, "LANDLORD", null, "INVITED", phone);
    UUID settled = room("SETTLED", "TENANT");
    seat(settled, "TENANT", inviter, "CONNECTED", null);
    seat(settled, "LANDLORD", null, "INVITED", phone);

    list(me)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].partyId").value(endedSeat.toString()));
  }

  @Test
  void 합류_전_기록이면_보이지_않는다() throws Exception {
    UUID target = room("LIVING", "TENANT");
    UUID joined = room("LIVING", "TENANT");
    jdbc.update(
        "UPDATE state.room_record SET joined_into_id = ?, joined_at = CURRENT_TIMESTAMP WHERE id = ?",
        target,
        joined);
    seat(joined, "TENANT", inviter, "CONNECTED", null);
    seat(joined, "LANDLORD", null, "INVITED", phone);

    list(me).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void 내가_이미_당사자인_호실의_초대는_보이지_않는다() throws Exception {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", me, "CONNECTED", null);
    seat(room, "LANDLORD", null, "INVITED", phone);

    list(me).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void 인증_번호가_없으면_빈_목록이다() throws Exception {
    // 06 ACC-4c: 다른 계정이 번호를 인증해 내 번호가 풀렸으면 옛 번호로 받은 초대도 보이지 않는다
    invitedRoom();
    jdbc.update("UPDATE state.member SET phone = NULL, phone_verified_at = NULL WHERE id = ?", me);

    list(me).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void 초대한_사람이_탈퇴했으면_이름이_없다() throws Exception {
    UUID gone = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, withdrawn_at, created_at, version)"
            + " VALUES (?, 'WITHDRAWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
        gone);
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", gone, "WITHDRAWN", null);
    seat(room, "LANDLORD", null, "INVITED", phone);

    list(me)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].inviterName").doesNotExist());
  }

  @Test
  void 로그인하지_않으면_401이다() throws Exception {
    mvc.perform(get("/api/invitations")).andExpect(status().isUnauthorized());
  }

  private ResultActions list(UUID memberId) throws Exception {
    return mvc.perform(get("/api/invitations").with(loginAs(memberId)));
  }

  /** 초대한 사람이 세입자로 만든 거주 중 호실과 내 번호로 초대된 집주인 자리. 그 자리 ID. */
  private UUID invitedRoom() {
    UUID room = room("LIVING", "TENANT");
    seat(room, "TENANT", inviter, "CONNECTED", null);
    return seat(room, "LANDLORD", null, "INVITED", phone);
  }

  private UUID room(String roomStatus, String createdBySide) {
    UUID id = v7(CLOCK.incrementAndGet());
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, ended_at, settled_at, created_at, version)
        VALUES (?, ?, '302호', ?, ?,
          CASE WHEN CAST(? AS varchar) = 'LIVING' THEN NULL ELSE CURRENT_TIMESTAMP END,
          CASE WHEN CAST(? AS varchar) = 'SETTLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          CURRENT_TIMESTAMP, 0)
        """,
        id,
        REGION,
        roomStatus,
        createdBySide,
        roomStatus,
        roomStatus);
    return id;
  }

  private UUID seat(UUID room, String side, UUID memberId, String seatStatus, String invitePhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, invite_phone, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        id,
        room,
        side,
        memberId,
        invitePhone,
        seatStatus);
    return id;
  }

  private UUID member(String displayName, String verifiedPhone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.member (id, display_name, phone, phone_verified_at, status, created_at, version)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 'ACTIVE', CURRENT_TIMESTAMP, 0)
        """,
        id,
        displayName,
        verifiedPhone);
    return id;
  }

  /** 다른 테스트와 겹치지 않는 휴대폰 번호(숫자만). */
  private static String newPhone() {
    return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }

  private static UUID v7(long epochMillis) {
    long msb = (epochMillis << 16) | 0x7000L;
    long lsb =
        (UUID.randomUUID().getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL) | Long.MIN_VALUE;
    return new UUID(msb, lsb);
  }
}
