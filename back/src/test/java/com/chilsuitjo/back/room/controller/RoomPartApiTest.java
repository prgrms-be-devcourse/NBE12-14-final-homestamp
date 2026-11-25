package com.chilsuitjo.back.room.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.room.rule.PartZone;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 06 ROOM-3 부위 API(#178, #136). 보안·pipeline·진짜 잠금·행동 기록(PostgreSQL)까지 띄운다. 호실은 {@code POST
 * /api/rooms}로 만들어 기본 29개 부위로 시작한다.
 *
 * <p>pipeline이 바깥 트랜잭션을 거절해서 {@code @Transactional}로 되돌리지 않는다. 테스트마다 새 회원·호실을 만든다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomPartApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  private UUID member;
  private UUID room;

  @BeforeEach
  void setUp() throws Exception {
    member = member();
    room = roomOf(member);
  }

  // ---- 목록

  @Test
  void 구역_이름은_V7_기본_목록과_글자까지_같다() {
    List<String> seeded =
        jdbc.queryForList(
            "SELECT zone FROM state.part_template GROUP BY zone ORDER BY min(sort_order)",
            String.class);

    assertThat(seeded).isEqualTo(PartZone.ALL);
  }

  @Test
  void 목록은_기본_29개를_구역_순서로_주고_설비_가전만_켜고_끌_수_있다() throws Exception {
    JsonNode parts = list();

    assertThat(parts).hasSize(29);
    assertThat(parts.get(0).get("zone").asString()).isEqualTo("현관");
    assertThat(parts.get(28).get("zone").asString()).isEqualTo("기타");
    for (JsonNode part : parts) {
      assertThat(part.get("hidden").asBoolean()).isFalse();
      assertThat(part.get("enabled").asBoolean()).isTrue();
      assertThat(part.get("toggleable").asBoolean())
          .isEqualTo(part.get("zone").asString().equals("설비·가전"));
    }
  }

  @Test
  void 목록_조회는_Idempotency_Key를_받지_않아도_되고_당사자가_아니면_NOT_FOUND() throws Exception {
    mvc.perform(get("/api/rooms/{id}/parts", room).with(loginAs(member)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/rooms/{id}/parts", room).with(loginAs(member())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/rooms/{id}/parts", UUID.randomUUID()).with(loginAs(member)))
        .andExpect(status().isNotFound());
  }

  // ---- 추가

  @Test
  void 부위를_더하면_그_구역_맨_뒤에_보이고_ADD_PART가_그_부위를_대상으로_남는다() throws Exception {
    JsonNode added =
        body(
            add(key(), "{\"zone\":\"방\",\"name\":\"  다락  \"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
    UUID partId = UUID.fromString(added.get("id").asString());
    assertThat(added.get("name").asString()).isEqualTo("다락"); // 앞뒤 공백을 자른다
    assertThat(added.get("enabled").asBoolean()).isTrue();
    assertThat(added.get("hidden").asBoolean()).isFalse();
    assertThat(added.get("toggleable").asBoolean()).isFalse();

    List<String> roomZone = new ArrayList<>();
    for (JsonNode part : list()) {
      if (part.get("zone").asString().equals("방")) {
        roomZone.add(part.get("name").asString());
      }
    }
    assertThat(roomZone).hasSize(9).last().isEqualTo("다락");

    Map<String, Object> action = lastAction();
    assertThat(action.get("type")).isEqualTo("ADD_PART");
    assertThat(action.get("room_part_id")).isEqualTo(partId);
    assertThat(action.get("payload")).isNull();
    assertThat(action.get("content_digest")).isNull();
  }

  @Test
  void 같은_이름도_더할_수_있다() throws Exception {
    add(key(), "{\"zone\":\"방\",\"name\":\"천장\"}").andExpect(status().isCreated());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM state.room_part WHERE room_record_id = ? AND name = '천장'",
                Integer.class,
                room))
        .isEqualTo(3); // 방·욕실 기본 + 새로 더한 것
  }

  @Test
  void 같은_키로_다시_오면_새로_더하지_않고_처음_부위를_돌려준다() throws Exception {
    String key = key();
    String body = "{\"zone\":\"주방\",\"name\":\"식탁\"}";
    String first = body(add(key, body).andExpect(status().isCreated())).get("id").asString();
    String again =
        body(add(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")))
            .get("id")
            .asString();

    assertThat(again).isEqualTo(first);
    assertThat(parts()).isEqualTo(30);
  }

  @Test
  void 구역이_6구역이_아니거나_이름이_비었거나_50자를_넘으면_VALIDATION_FAILED() throws Exception {
    for (String body :
        List.of(
            "{\"zone\":\"안방\",\"name\":\"벽\"}", // 6구역이 아님
            "{\"zone\":\" 방\",\"name\":\"벽\"}", // 글자까지 같아야 함
            "{\"zone\":\"설비가전\",\"name\":\"벽\"}",
            "{\"name\":\"벽\"}",
            "{\"zone\":\"방\",\"name\":\"   \"}",
            "{\"zone\":\"방\"}",
            "{\"zone\":\"방\",\"name\":\"" + "가".repeat(51) + "\"}")) {
      add(key(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    add(key(), "{\"zone\":\"방\",\"name\":\"" + "가".repeat(50) + "\"}")
        .andExpect(status().isCreated());
    assertThat(parts()).isEqualTo(30);
  }

  @Test
  void 이름에_NUL_문자가_있으면_VALIDATION_FAILED() throws Exception {
    for (String name : List.of("\\u0000", "다\\u0000락", "다락\\u0000")) {
      add(key(), "{\"zone\":\"방\",\"name\":\"" + name + "\"}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(parts()).isEqualTo(29);
  }

  @Test
  void 멱등_키가_없으면_VALIDATION_FAILED() throws Exception {
    mvc.perform(
            post("/api/rooms/{id}/parts", room)
                .with(loginAs(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"방\",\"name\":\"다락\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ---- 숨김·다시 보이기 (ROOM-3a·3b)

  @Test
  void 숨기면_목록에_숨김으로_남고_그_부위에_하자를_등록할_수_없다() throws Exception {
    UUID closet = partNamed("붙박이장");

    JsonNode hidden = body(action(closet, "hide", key(), null).andExpect(status().isOk()));
    assertThat(hidden.get("hidden").asBoolean()).isTrue();

    JsonNode inList = find(list(), closet);
    assertThat(inList.get("hidden").asBoolean()).isTrue(); // 응답에는 남고, 기본 화면 목록에서 빼는 것은 화면
    assertThat(lastAction().get("type")).isEqualTo("HIDE_PART");
    assertThat(lastAction().get("room_part_id")).isEqualTo(closet);

    registerRepair(closet)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void 숨긴_부위를_다시_보이게_하면_목록에_돌아오고_하자를_등록할_수_있다() throws Exception {
    UUID closet = partNamed("붙박이장");
    action(closet, "hide", key(), null).andExpect(status().isOk());

    JsonNode shown = body(action(closet, "show", key(), null).andExpect(status().isOk()));
    assertThat(shown.get("hidden").asBoolean()).isFalse();
    assertThat(find(list(), closet).get("hidden").asBoolean()).isFalse();
    assertThat(lastAction().get("type")).isEqualTo("SHOW_PART");
    assertThat(lastAction().get("room_part_id")).isEqualTo(closet);
    assertThat(
            jdbc.queryForObject(
                "SELECT hidden_at FROM state.room_part WHERE id = ?", Timestamp.class, closet))
        .isNull();

    registerRepair(closet).andExpect(status().isCreated());
  }

  @Test
  void 숨긴_부위를_또_숨기거나_보이는_부위를_다시_보이게_하면_INVALID_STATE() throws Exception {
    UUID closet = partNamed("붙박이장");

    action(closet, "show", key(), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    action(closet, "hide", key(), null).andExpect(status().isOk());
    action(closet, "hide", key(), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
  }

  @Test
  void 같은_키로_다시_숨기면_행동을_더_남기지_않고_처음_결과를_돌려준다() throws Exception {
    UUID closet = partNamed("붙박이장");
    String key = key();
    long before = actions();

    action(closet, "hide", key, null).andExpect(status().isOk());
    action(closet, "hide", key, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.hidden").value(true));

    assertThat(actions()).isEqualTo(before + 1);
  }

  @Test
  void 남의_호실_부위나_없는_부위는_NOT_FOUND이고_남의_호실에는_손댈_수_없다() throws Exception {
    UUID closet = partNamed("붙박이장");
    UUID stranger = member();

    // 다른 호실 경로로 이 호실의 부위를 부름: 그 호실에는 이 부위가 없다
    UUID otherRoom = roomOf(stranger);
    mvc.perform(
            post("/api/rooms/{room}/parts/{part}/hide", otherRoom, closet)
                .with(loginAs(stranger))
                .header("Idempotency-Key", key()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    action(UUID.randomUUID(), "hide", key(), null).andExpect(status().isNotFound());
    // 당사자가 아닌 회원
    mvc.perform(
            post("/api/rooms/{room}/parts/{part}/hide", room, closet)
                .with(loginAs(stranger))
                .header("Idempotency-Key", key()))
        .andExpect(status().isNotFound());

    assertThat(find(list(), closet).get("hidden").asBoolean()).isFalse();
  }

  // ---- 켜고 끔

  @Test
  void 설비_가전은_끄고_켤_수_있고_숨김과_따로_움직인다() throws Exception {
    UUID fridge = partNamed("냉장고");

    JsonNode off =
        body(action(fridge, "toggle", key(), "{\"enabled\":false}").andExpect(status().isOk()));
    assertThat(off.get("enabled").asBoolean()).isFalse();
    assertThat(off.get("hidden").asBoolean()).isFalse();
    Map<String, Object> toggled = lastAction();
    assertThat(toggled.get("type")).isEqualTo("TOGGLE_PART");
    assertThat(toggled.get("room_part_id")).isEqualTo(fridge);
    assertThat(toggled.get("payload").toString()).contains("\"enabled\"").contains("false");

    action(fridge, "hide", key(), null).andExpect(status().isOk());
    JsonNode on =
        body(action(fridge, "toggle", key(), "{\"enabled\":true}").andExpect(status().isOk()));
    assertThat(on.get("enabled").asBoolean()).isTrue();
    assertThat(on.get("hidden").asBoolean()).isTrue();
  }

  @Test
  void 이미_그_값이면_INVALID_STATE이고_설비_가전이_아니면_VALIDATION_FAILED() throws Exception {
    action(partNamed("냉장고"), "toggle", key(), "{\"enabled\":true}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    action(partNamed("붙박이장"), "toggle", key(), "{\"enabled\":false}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    UUID added =
        UUID.fromString(
            body(add(key(), "{\"zone\":\"설비·가전\",\"name\":\"건조기\"}")).get("id").asString());
    action(added, "toggle", key(), "{\"enabled\":false}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    action(partNamed("냉장고"), "toggle", key(), "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ---- 공유를 그만둔 뒤 보이는 범위 (03 6-6 9번, 3-1, 불변식 18, #180 리뷰)

  @Test
  void 공유를_그만두지_않았으면_asOf가_없고_지금_상태_그대로다() throws Exception {
    connectLandlord();

    JsonNode listed = listOf(member);
    assertThat(listed.has("asOf") && listed.get("asOf").isNull()).isTrue();
    assertThat(listed.get("parts")).hasSize(29);
  }

  @Test
  void 세입자가_그만둔_뒤_집주인이_더하고_숨기고_다시_보이고_끈_것은_세입자에게_보이지_않는다() throws Exception {
    UUID landlord = connectLandlord();
    UUID closet = partNamed("붙박이장");
    UUID shoeRack = partNamed("신발장");
    UUID fridge = partNamed("냉장고");
    // 그만두기 전: 세입자가 신발장을 숨기고 집주인이 붙박이장을 숨김 → 양쪽 모두 보인다
    action(shoeRack, "hide", key(), null).andExpect(status().isOk());
    actionAs(landlord, closet, "hide", null).andExpect(status().isOk());
    Timestamp stoppedAt = stopSharing("TENANT");

    UUID added =
        UUID.fromString(
            body(addAs(landlord, "{\"zone\":\"방\",\"name\":\"다락\"}")
                    .andExpect(status().isCreated()))
                .get("id")
                .asString());
    actionAs(landlord, closet, "show", null).andExpect(status().isOk());
    actionAs(landlord, partNamed("현관문·도어락"), "hide", null).andExpect(status().isOk());
    actionAs(landlord, fridge, "toggle", "{\"enabled\":false}").andExpect(status().isOk());

    JsonNode tenantView = listOf(member);
    assertThat(tenantView.get("asOf").asString()).isEqualTo(stoppedAt.toInstant().toString());
    JsonNode tenantParts = tenantView.get("parts");
    assertThat(tenantParts).hasSize(29);
    assertThat(has(tenantParts, added)).isFalse();
    assertThat(find(tenantParts, closet).get("hidden").asBoolean()).isTrue(); // 다시 보이기가 안 보임
    assertThat(find(tenantParts, partNamed("현관문·도어락")).get("hidden").asBoolean()).isFalse();
    assertThat(find(tenantParts, fridge).get("enabled").asBoolean()).isTrue();
    assertThat(find(tenantParts, shoeRack).get("hidden").asBoolean()).isTrue(); // 그만두기 전 내 행동

    // 남은 집주인은 자기 행동을 본다(내 측 ○). 고정 시각은 양쪽에 같다
    JsonNode landlordView = listOf(landlord);
    assertThat(landlordView.get("asOf").asString()).isEqualTo(stoppedAt.toInstant().toString());
    JsonNode landlordParts = landlordView.get("parts");
    assertThat(landlordParts).hasSize(30);
    assertThat(find(landlordParts, added).get("hidden").asBoolean()).isFalse();
    assertThat(find(landlordParts, closet).get("hidden").asBoolean()).isFalse();
    assertThat(find(landlordParts, partNamed("현관문·도어락")).get("hidden").asBoolean()).isTrue();
    assertThat(find(landlordParts, fridge).get("enabled").asBoolean()).isFalse();
  }

  @Test
  void 집주인이_그만둔_뒤_세입자가_바꾼_것도_집주인에게_보이지_않는다() throws Exception {
    UUID landlord = connectLandlord();
    UUID closet = partNamed("붙박이장");
    action(closet, "hide", key(), null).andExpect(status().isOk());
    stopSharing("LANDLORD");

    action(closet, "show", key(), null).andExpect(status().isOk());
    UUID added =
        UUID.fromString(
            body(add(key(), "{\"zone\":\"주방\",\"name\":\"식탁\"}").andExpect(status().isCreated()))
                .get("id")
                .asString());

    JsonNode landlordParts = listOf(landlord).get("parts");
    assertThat(find(landlordParts, closet).get("hidden").asBoolean()).isTrue();
    assertThat(has(landlordParts, added)).isFalse();
    assertThat(find(list(), closet).get("hidden").asBoolean()).isFalse();
    assertThat(has(list(), added)).isTrue();
  }

  @Test
  void 공유를_그만둔_사람은_부위를_더하거나_숨기거나_다시_보이거나_켜고_끌_수_없다() throws Exception {
    connectLandlord();
    UUID closet = partNamed("붙박이장");
    action(closet, "hide", key(), null).andExpect(status().isOk());
    stopSharing("TENANT");
    long before = actions();

    add(key(), "{\"zone\":\"방\",\"name\":\"다락\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    action(partNamed("신발장"), "hide", key(), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    action(closet, "show", key(), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));
    action(partNamed("냉장고"), "toggle", key(), "{\"enabled\":false}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHARING_STOPPED"));

    assertThat(actions()).isEqualTo(before);
  }

  @Test
  void 상대가_탈퇴했으면_그만둔_시각이_남아_있어도_고정하지_않는다() throws Exception {
    UUID landlord = connectLandlord();
    stopSharing("TENANT");
    UUID closet = partNamed("붙박이장");
    actionAs(landlord, closet, "hide", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    JsonNode listed = listOf(member);
    assertThat(listed.has("asOf") && listed.get("asOf").isNull()).isTrue();
    assertThat(find(listed.get("parts"), closet).get("hidden").asBoolean()).isTrue();
  }

  @Test
  void 다시_공유하면_그만둔_동안의_변경도_보인다() throws Exception {
    UUID landlord = connectLandlord();
    stopSharing("TENANT");
    UUID closet = partNamed("붙박이장");
    actionAs(landlord, closet, "hide", null).andExpect(status().isOk());
    assertThat(find(list(), closet).get("hidden").asBoolean()).isFalse();

    jdbc.update(
        "UPDATE state.party SET status = 'CONNECTED', sharing_stopped_at = NULL"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);

    JsonNode listed = listOf(member);
    assertThat(listed.has("asOf") && listed.get("asOf").isNull()).isTrue();
    assertThat(find(listed.get("parts"), closet).get("hidden").asBoolean()).isTrue();
  }

  @Test
  void 공유를_그만둔_뒤_같은_키로_다시_보내도_그만둔_뒤_상대가_바꾼_부위_상태는_보이지_않는다() throws Exception {
    // 세입자가 숨김·추가를 한 뒤 공유를 그만둠 → 집주인이 그 부위들을 바꿈 → 세입자가 같은 키로 다시 보냄(06 4-0 재응답)
    UUID landlord = connectLandlord();
    UUID closet = partNamed("붙박이장");
    String hideKey = key();
    action(closet, "hide", hideKey, null).andExpect(status().isOk());
    String addKey = key();
    String addBody = "{\"zone\":\"방\",\"name\":\"다락\"}";
    UUID added =
        UUID.fromString(
            body(add(addKey, addBody).andExpect(status().isCreated())).get("id").asString());
    stopSharing("TENANT");
    actionAs(landlord, closet, "show", null).andExpect(status().isOk());
    actionAs(landlord, added, "hide", null).andExpect(status().isOk());

    action(closet, "hide", hideKey, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.hidden").value(true));
    add(addKey, addBody)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(added.toString()))
        .andExpect(jsonPath("$.hidden").value(false));
  }

  // ---- 준비물

  private ResultActions add(String key, String body) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/parts", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions action(UUID partId, String verb, String key, String body) throws Exception {
    var request =
        post("/api/rooms/{room}/parts/{part}/" + verb, room, partId)
            .with(loginAs(member))
            .header("Idempotency-Key", key);
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(request);
  }

  /** 하자 등록(REP-1). 사진은 가짜 사진 창구가 받는다. */
  private ResultActions registerRepair(UUID partId) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", room)
            .with(loginAs(member))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + partId
                    + "\",\"title\":\"문이 안 닫혀요\",\"uploadTickets\":[\""
                    + UUID.randomUUID()
                    + "\"]}"));
  }

  private JsonNode list() throws Exception {
    return listOf(member).get("parts");
  }

  /** {@code {asOf, parts}} 전체. */
  private JsonNode listOf(UUID viewer) throws Exception {
    return body(
        mvc.perform(get("/api/rooms/{id}/parts", room).with(loginAs(viewer)))
            .andExpect(status().isOk()));
  }

  private static boolean has(JsonNode parts, UUID id) {
    for (JsonNode part : parts) {
      if (part.get("id").asString().equals(id.toString())) {
        return true;
      }
    }
    return false;
  }

  /** 집주인 자리에 회원을 연결한다(초대·연결 API는 아직 없음, #163). */
  private UUID connectLandlord() {
    UUID landlord = member();
    jdbc.update(
        "UPDATE state.party SET member_id = ?, status = 'CONNECTED',"
            + " connected_at = CURRENT_TIMESTAMP WHERE room_record_id = ? AND side = 'LANDLORD'",
        landlord,
        room);
    return landlord;
  }

  /**
   * 이 측이 공유를 그만둔다. 시각은 이 호실의 마지막 행동 시각이라(앱 시계) 그때까지의 행동은 보이고 그 뒤 요청은 모두 뒤가 된다. 공유 그만두기 API는 아직 없어
   * 행만 바꾼다.
   */
  private Timestamp stopSharing(String side) {
    Timestamp at =
        jdbc.queryForObject(
            "SELECT max(created_at) FROM record.action WHERE room_record_id = ?",
            Timestamp.class,
            room);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        at,
        room,
        side);
    return at;
  }

  private ResultActions actionAs(UUID actor, UUID partId, String verb, String body)
      throws Exception {
    var request =
        post("/api/rooms/{room}/parts/{part}/" + verb, room, partId)
            .with(loginAs(actor))
            .header("Idempotency-Key", key());
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(request);
  }

  private ResultActions addAs(UUID actor, String body) throws Exception {
    return mvc.perform(
        post("/api/rooms/{id}/parts", room)
            .with(loginAs(actor))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private JsonNode body(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private static JsonNode find(JsonNode parts, UUID id) {
    for (JsonNode part : parts) {
      if (part.get("id").asString().equals(id.toString())) {
        return part;
      }
    }
    throw new AssertionError("목록에 부위가 없어요: " + id);
  }

  private UUID partNamed(String name) {
    return jdbc.queryForObject(
        "SELECT id FROM state.room_part WHERE room_record_id = ? AND name = ?",
        UUID.class,
        room,
        name);
  }

  /** {@code POST /api/rooms}로 거주 중인 호실을 만든다(기본 29개 부위). */
  private UUID roomOf(UUID owner) throws Exception {
    String region =
        jdbc.queryForObject(
            "SELECT code FROM state.region WHERE active ORDER BY code LIMIT 1", String.class);
    String created =
        mvc.perform(
                post("/api/rooms")
                    .with(loginAs(owner))
                    .header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"side\":\"TENANT\",\"regionCode\":\""
                            + region
                            + "\",\"alias\":\"부위 시험\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private Map<String, Object> lastAction() {
    return jdbc.queryForMap(
        "SELECT type, room_part_id, payload::text AS payload, content_digest FROM record.action"
            + " WHERE room_record_id = ? ORDER BY room_seq DESC LIMIT 1",
        room);
  }

  private long actions() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE room_record_id = ?", Long.class, room);
  }

  private int parts() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM state.room_part WHERE room_record_id = ?", Integer.class, room);
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
