package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionBatch;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.RoomGate;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.RoomGateReader;
import com.chilsuitjo.back.action.service.ActionPipeline;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.error.BusinessException;
import com.chilsuitjo.back.global.error.ErrorCode;
import com.chilsuitjo.back.room.dto.RoomAccess;
import com.chilsuitjo.back.room.rule.PartyStatus;
import com.chilsuitjo.back.room.rule.RoomStatus;
import com.chilsuitjo.back.room.service.RoomAccessService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 06 4-3 {@code POST /api/rooms}(ROOM-1a)와 실제 권한 검사(ROOM-2a). 보안 설정까지 띄워 테스트 키로 서명한 액세스 토큰({@code
 * SecurityTestSupport.loginAs})으로 부른다(#115 뒤 {@code Bearer <회원 ID>}는 받지 않음).
 *
 * <p>호실을 만드는 요청은 pipeline이 트랜잭션을 열므로 테스트에 {@code @Transactional}을 두지 않는다. 행동 기록은 지울 수 없는 표(A)라
 * 테스트마다 지우지 않고 회원·별칭·키를 새로 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomCreateApiTest {

  /** 기본 6구역 29개(V7 시드, #142). */
  private static final int TEMPLATE_COUNT = 29;

  /** 코드표에 넣어 두는 고를 수 없는 동(없어진 동은 지우지 않고 {@code active = false}). */
  private static final String INACTIVE_REGION = "9999999990";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RoomAccessService access;
  @Autowired private RoomGateReader gates;
  @Autowired private ActionPipeline pipeline;
  @Autowired private PlatformTransactionManager transactionManager;

  private String region;

  @BeforeEach
  void fixedData() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong, active) VALUES (?, '테스트', '없어진동', false)"
            + " ON CONFLICT (code) DO NOTHING",
        INACTIVE_REGION);
    region =
        jdbc.queryForObject(
            "SELECT code FROM state.region WHERE active ORDER BY code LIMIT 1", String.class);
  }

  @Test
  void 임차인으로_만들면_거주_중인_호실과_내_자리_반대쪽_빈_자리가_생긴다() throws Exception {
    UUID member = member();
    String alias = alias();

    UUID room =
        created(
            create(member, key(), body("TENANT", region, alias, null))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));

    Map<String, Object> row =
        jdbc.queryForMap("SELECT * FROM state.room_record WHERE id = ?", room);
    assertThat(row.get("status")).isEqualTo("LIVING");
    assertThat(row.get("created_by_side")).isEqualTo("TENANT");
    assertThat(row.get("region_code")).isEqualTo(region);
    assertThat(row.get("alias")).isEqualTo(alias);
    assertThat(row.get("move_in_date")).isNull(); // 입주일은 비워 둘 수 있다(ROOM-1a)
    assertThat(row.get("counterpart_first_connected_at")).isNull();

    assertThat(seats(room))
        .containsExactlyInAnyOrder(
            List.of("TENANT", member.toString(), "CONNECTED"), List.of("LANDLORD", "-", "EMPTY"));
  }

  @Test
  void 임대인으로_만들면_임대인_자리가_내_자리이고_입주일이_저장된다() throws Exception {
    UUID member = member();

    UUID room =
        created(
            create(member, key(), body("LANDLORD", region, alias(), "2026-12-01"))
                .andExpect(status().isCreated()));

    assertThat(
            jdbc.queryForObject(
                "SELECT created_by_side FROM state.room_record WHERE id = ?", String.class, room))
        .isEqualTo("LANDLORD");
    assertThat(
            jdbc.queryForObject(
                "SELECT move_in_date::text FROM state.room_record WHERE id = ?",
                String.class,
                room))
        .isEqualTo("2026-12-01");
    assertThat(seats(room))
        .containsExactlyInAnyOrder(
            List.of("LANDLORD", member.toString(), "CONNECTED"), List.of("TENANT", "-", "EMPTY"));
  }

  @Test
  void 기본_부위_29개가_이_호실의_부위로_그대로_복사된다() throws Exception {
    UUID room =
        created(
            create(member(), key(), body("TENANT", region, alias(), null))
                .andExpect(status().isCreated()));

    List<Map<String, Object>> copied =
        jdbc.queryForList(
            "SELECT template_id, zone, name, sort_order, enabled, hidden_at"
                + " FROM state.room_part WHERE room_record_id = ? ORDER BY template_id",
            room);
    List<Map<String, Object>> templates =
        jdbc.queryForList(
            "SELECT id AS template_id, zone, name, sort_order FROM state.part_template ORDER BY id");

    assertThat(copied).hasSize(TEMPLATE_COUNT).hasSameSizeAs(templates);
    for (int i = 0; i < copied.size(); i++) {
      Map<String, Object> part = copied.get(i);
      Map<String, Object> template = templates.get(i);
      assertThat(part.get("template_id")).isEqualTo(template.get("template_id"));
      assertThat(part.get("zone")).isEqualTo(template.get("zone"));
      assertThat(part.get("name")).isEqualTo(template.get("name"));
      assertThat(part.get("sort_order")).isEqualTo(template.get("sort_order"));
      assertThat(part.get("enabled")).isEqualTo(true);
      assertThat(part.get("hidden_at")).isNull();
    }
  }

  @Test
  void 호실_만들기_행동이_새_호실의_첫_행동으로_남는다() throws Exception {
    UUID member = member();

    UUID room =
        created(
            create(member, key(), body("LANDLORD", region, alias(), null))
                .andExpect(status().isCreated()));

    List<Map<String, Object>> actions =
        jdbc.queryForList(
            "SELECT type, room_seq, actor_member_id, actor_side, payload"
                + " FROM record.action WHERE room_record_id = ?",
            room);
    assertThat(actions).hasSize(1);
    Map<String, Object> action = actions.getFirst();
    assertThat(action.get("type")).isEqualTo("CREATE_ROOM");
    assertThat(action.get("room_seq")).isEqualTo(1L);
    assertThat(action.get("actor_member_id")).isEqualTo(member);
    assertThat(action.get("actor_side")).isEqualTo("LANDLORD");
    assertThat(action.get("payload")).isNull();
  }

  @Test
  void 같은_키로_다시_오면_새로_만들지_않고_처음_호실을_돌려준다() throws Exception {
    UUID member = member();
    String key = key();
    String alias = alias();

    UUID first =
        created(
            create(member, key, body("TENANT", region, alias, null))
                .andExpect(status().isCreated()));
    UUID again =
        created(
            create(member, key, body("TENANT", region, alias, null))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));

    assertThat(again).isEqualTo(first);
    assertThat(roomsOf(member)).isEqualTo(1);
  }

  @Test
  void 코드표에_없거나_고를_수_없는_법정동이면_VALIDATION_FAILED이고_아무것도_남지_않는다() throws Exception {
    UUID member = member();

    for (String code : List.of("0000000000", INACTIVE_REGION, "11110", "11110abcde")) {
      create(member, key(), body("TENANT", code, alias(), null))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(roomsOf(member)).isZero();
  }

  @Test
  void 필수_입력이_빠지거나_별칭이_길면_VALIDATION_FAILED다() throws Exception {
    UUID member = member();
    List<String> bodies =
        List.of(
            body(null, region, alias(), null), // 내 쪽 없음
            body("OWNER", region, alias(), null), // 없는 쪽
            body("TENANT", null, alias(), null), // 주소 없음
            body("TENANT", region, null, null), // 별칭 없음
            body("TENANT", region, "  ", null), // 별칭이 공백뿐
            body("TENANT", region, "가".repeat(51), null), // DB 칸(50)보다 김
            body("TENANT", region, alias(), "2026-13-01")); // 날짜 아님

    for (String body : bodies) {
      create(member, key(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(roomsOf(member)).isZero();
  }

  @Test
  void 멱등_키가_없거나_형식이_틀리면_VALIDATION_FAILED다() throws Exception {
    UUID member = member();
    String body = body("TENANT", region, alias(), null);

    mvc.perform(
            post("/api/rooms")
                .with(loginAs(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    create(member, "a#2", body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(roomsOf(member)).isZero();
  }

  @Test
  void 로그인하지_않으면_만들_수_없다() throws Exception {
    mvc.perform(
            post("/api/rooms")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TENANT", region, alias(), null)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 만드는_도중_실패하면_호실도_자리도_부위도_남지_않는다() throws Exception {
    // 회원 행이 없는 ID: 호실 행을 넣은 뒤 당사자 자리의 회원 FK에서 실패한다(같은 트랜잭션이라 함께 되돌려짐)
    UUID ghost = UUID.randomUUID();
    String alias = alias();

    create(ghost, key(), body("TENANT", region, alias, null))
        .andExpect(status().isInternalServerError());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.room_record WHERE alias = ?", Integer.class, alias))
        .isZero();
    assertThat(roomsOf(ghost)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record.action WHERE actor_key = ?",
                Integer.class,
                ghost.toString()))
        .isZero();
  }

  @Test
  void 만든_회원은_자기_호실에_접근하고_자기_자리와_상태를_받는다() throws Exception {
    UUID member = member();
    UUID room = createdRoom(member, "TENANT");

    RoomAccess mine = access.require(room, member);

    assertThat(mine.roomRecordId()).isEqualTo(room);
    assertThat(mine.memberId()).isEqualTo(member);
    assertThat(mine.partyId()).isEqualTo(partyOf(room, member));
    assertThat(mine.side()).isEqualTo(PartySide.TENANT);
    assertThat(mine.myStatus()).isEqualTo(PartyStatus.CONNECTED);
    assertThat(mine.roomStatus()).isEqualTo(RoomStatus.LIVING);
    assertThat(mine.joinedIntoRoomId()).isNull();
    assertThat(mine.counterpartStatus()).isEqualTo(PartyStatus.EMPTY);
    assertThat(mine.sharingStoppedAt()).isNull();
  }

  @Test
  void 다른_회원의_호실과_없는_호실은_NOT_FOUND다() throws Exception {
    UUID owner = member();
    UUID stranger = member();
    UUID room = createdRoom(owner, "TENANT");
    UUID strangersRoom = createdRoom(stranger, "LANDLORD");

    expectNotFound(() -> access.require(room, stranger));
    expectNotFound(() -> access.require(strangersRoom, owner));
    expectNotFound(() -> access.require(UUID.randomUUID(), owner));
  }

  @Test
  void 잠금_읽기는_당사자의_측을_채우고_남에게는_NOT_FOUND다() throws Exception {
    UUID owner = member();
    UUID room = createdRoom(owner, "LANDLORD");

    RoomGate gate = inTransaction(() -> gates.lockAndRead(room, Actor.member(owner)));
    assertThat(gate).isEqualTo(RoomGate.open(room, PartySide.LANDLORD));

    RoomGate server = inTransaction(() -> gates.lockAndRead(room, Actor.server()));
    assertThat(server).isEqualTo(RoomGate.open(room, null));

    expectNotFound(() -> inTransaction(() -> gates.lockAndRead(room, Actor.member(member()))));
    expectNotFound(
        () -> inTransaction(() -> gates.lockAndRead(UUID.randomUUID(), Actor.member(owner))));
    // 잠금은 pipeline 트랜잭션 안에서만 의미가 있다
    assertThatThrownBy(() -> gates.lockAndRead(room, Actor.member(owner)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void 만든_호실에_이어지는_행동은_실제_잠금을_지나고_남의_행동은_막힌다() throws Exception {
    UUID owner = member();
    UUID stranger = member();
    UUID room = createdRoom(owner, "TENANT");

    pipeline.executeAll(
        ActionBatch.of(room, Actor.member(owner), key())
            .record(ActionType.SHARE_CONSENT, Map.of()));
    expectNotFound(() ->
        pipeline.executeAll(
            ActionBatch.of(room, Actor.member(stranger), key())
                .record(ActionType.SHARE_CONSENT, Map.of())));

    List<Map<String, Object>> actions =
        jdbc.queryForList(
            "SELECT type, room_seq, actor_side FROM record.action"
                + " WHERE room_record_id = ? ORDER BY room_seq",
            room);
    assertThat(actions)
        .extracting(a -> List.of(a.get("type"), a.get("room_seq"), a.get("actor_side")))
        .containsExactly(
            List.of("CREATE_ROOM", 1L, "TENANT"), List.of("SHARE_CONSENT", 2L, "TENANT"));
  }

  // ---- 준비물

  private ResultActions create(UUID member, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/rooms")
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private UUID createdRoom(UUID member, String side) throws Exception {
    return created(
        create(member, key(), body(side, region, alias(), null)).andExpect(status().isCreated()));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
  }

  private static String body(String side, String regionCode, String alias, String moveInDate) {
    return "{"
        + field("side", side)
        + ","
        + field("regionCode", regionCode)
        + ","
        + field("alias", alias)
        + ","
        + field("moveInDate", moveInDate)
        + "}";
  }

  private static String field(String name, String value) {
    return "\"" + name + "\":" + (value == null ? "null" : "\"" + value + "\"");
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  /** [측, 회원 ID 또는 "-", 상태]. */
  private List<List<String>> seats(UUID room) {
    return jdbc.query(
        "SELECT side, member_id, status FROM state.party WHERE room_record_id = ?",
        (rs, i) ->
            List.of(
                rs.getString("side"),
                rs.getString("member_id") == null ? "-" : rs.getString("member_id"),
                rs.getString("status")),
        room);
  }

  private UUID partyOf(UUID room, UUID member) {
    return jdbc.queryForObject(
        "SELECT id FROM state.party WHERE room_record_id = ? AND member_id = ?",
        UUID.class,
        room,
        member);
  }

  private int roomsOf(UUID member) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.party WHERE member_id = ?", Integer.class, member);
  }

  private <T> T inTransaction(Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  private static void expectNotFound(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String alias() {
    return "호실-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
