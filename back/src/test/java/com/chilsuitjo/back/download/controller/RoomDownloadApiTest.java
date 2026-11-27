package com.chilsuitjo.back.download.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.global.common.PartySide;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * 내려받기 최소판({@code GET /api/rooms/{id}/download}, 06 DL-1·3-12·S-19, #246). 응답의 {@code record}를 화면이
 * 그대로 {@code 기록.json}으로 저장하고, 사진은 {@code photoBatches}의 묶음마다 주소를 받아 ZIP에 넣는다(03 6-3 "가져가기").
 *
 * <p>주소 받기(묶음별 원본 5분 주소, DL-1b의 다시 받기)는 photo 쪽 {@code POST /api/photos/view-urls}({@code variant:
 * ORIGINAL})가 맡아(#246) 여기서는 묶음 나누기까지만 본다. 테스트마다 새 호실을 만들어 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomDownloadApiTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;
  private UUID tenant;
  private UUID landlord;
  private UUID part;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = insertRoom();
    tenant = member();
    landlord = member();
    part = part();
    seat(PartySide.TENANT, tenant);
    seat(PartySide.LANDLORD, landlord);
  }

  // ---- DL-1a 기록.json ----

  @Test
  void DL_1a_기록에는_내가_볼_수_있는_행동이_시각_순으로_들어간다() throws Exception {
    UUID caseId = register(1);
    respond(caseId, landlord, "NEED_MORE", "사진이 흐려요").andExpect(status().isOk());
    supplement(caseId, tenant, "다시 찍었어요").andExpect(status().isOk());

    String body =
        download(tenant).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    List<String> types = JsonPath.read(body, "$.record.actions[*].type");
    assertThat(types).containsExactly("REGISTER", "RESPOND_DEFECT", "SUPPLEMENT");
    List<String> sides = JsonPath.read(body, "$.record.actions[*].side");
    assertThat(sides).containsExactly("TENANT", "LANDLORD", "TENANT");
    List<String> at = JsonPath.read(body, "$.record.actions[*].at");
    assertThat(at).isSortedAccordingTo((a, b) -> Instant.parse(a).compareTo(Instant.parse(b)));
    List<String> targets = JsonPath.read(body, "$.record.actions[*].target.id");
    assertThat(targets).containsOnly(caseId.toString());
    assertThat((String) JsonPath.read(body, "$.record.actions[1].reason")).isEqualTo("사진이 흐려요");
    assertThat((String) JsonPath.read(body, "$.record.mySide")).isEqualTo("TENANT");
    assertThat((String) JsonPath.read(body, "$.record.notice")).isEqualTo("서비스에 남긴 기록의 사본");
    assertThat((String) JsonPath.read(body, "$.record.exportedAt")).isNotBlank();
  }

  @Test
  void DL_1a_기록에는_행동_번호와_해시와_회원_식별값과_서버_내부_값을_넣지_않는다() throws Exception {
    // 03 3-1 817줄: 행동 번호·해시는 화면·내려받기 어디에도 내보내지 않는다(불변식 18의 번호 틈)
    UUID caseId = register(1);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    ResultActions result = download(tenant).andExpect(status().isOk());
    for (int i = 0; i < 2; i++) {
      String action = "$.record.actions[" + i + "].";
      for (String hidden :
          List.of(
              "roomSeq",
              "room_seq",
              "prevHash",
              "hash",
              "contentDigest",
              "actorKey",
              "actorMemberId",
              "idempotencyKey",
              "requestId",
              "seenVersionNo")) {
        result.andExpect(jsonPath(action + hidden).doesNotExist());
      }
    }
    // 삭제 기록도 넣지 않는다(DL-1a)
    result.andExpect(jsonPath("$.record.deletions").doesNotExist());
  }

  @Test
  void DL_1a_사진마다_지문값이_있고_행동의_지문값과_사진_목록의_지문값이_같다() throws Exception {
    UUID caseId = register(3);

    String body =
        download(tenant).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    List<Map<String, Object>> onAction = JsonPath.read(body, "$.record.actions[0].photos");
    List<Map<String, Object>> photos = JsonPath.read(body, "$.record.photos");
    assertThat(onAction).hasSize(3);
    assertThat(photos).hasSize(3);
    for (Map<String, Object> photo : photos) {
      assertThat((String) photo.get("sha256")).matches("[0-9a-f]{64}");
      assertThat(photo.get("file")).isEqualTo("사진/" + photo.get("photoId") + ".jpg");
      assertThat(photo.get("uploadedBy")).isEqualTo("TENANT");
      assertThat(photo.get("erased")).isNull();
    }
    // 행동에 남은 지문값(05 2-3 "사진 지문은 행동 payload에")과 사진 목록의 지문값이 사진마다 같다
    for (Map<String, Object> ref : onAction) {
      Map<String, Object> photo =
          photos.stream()
              .filter(p -> p.get("photoId").equals(ref.get("photoId")))
              .findFirst()
              .orElseThrow();
      assertThat(ref.get("sha256")).isEqualTo(photo.get("sha256"));
      assertThat(ref.get("file")).isEqualTo(photo.get("file"));
    }
    List<String> targetIds = JsonPath.read(body, "$.record.photos[*].target.id");
    assertThat(targetIds).containsOnly(caseId.toString());
  }

  @Test
  void 공유를_그만둔_뒤_상대가_남긴_행동은_그만둔_쪽_내려받기에_들어가지_않는다() throws Exception {
    // 불변식 18 · 03 6-6 9번: 그만둔 시각 뒤의 상대 행동은 보는 사람에게 보이지 않는다. 내가 남긴 행동은 늘 보인다
    UUID caseId = register(1);
    Timestamp stoppedAt =
        jdbc.queryForObject(
            "SELECT created_at FROM state.repair_case WHERE id = ?", Timestamp.class, caseId);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        stoppedAt,
        room);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());

    String mine = body(download(tenant).andExpect(status().isOk()));
    List<String> tenantSees = JsonPath.read(mine, "$.record.actions[*].type");
    assertThat(tenantSees).containsExactly("REGISTER");

    // 남은 쪽은 자기가 남긴 답을 본다
    String theirs = body(download(landlord).andExpect(status().isOk()));
    List<String> landlordSees = JsonPath.read(theirs, "$.record.actions[*].type");
    assertThat(landlordSees).containsExactly("REGISTER", "RESPOND_DEFECT");
  }

  @Test
  void 공유를_그만둔_뒤_내가_등록한_하자와_사진은_상대_내려받기에_들어가지_않는다() throws Exception {
    // 세입자가 공유를 그만둔 뒤 등록한 하자는 나만 보는 기록이다(03 불변식 18)
    UUID before = register(1);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        room);
    UUID after = register(2);

    String theirs = body(download(landlord).andExpect(status().isOk()));
    List<String> landlordTargets = JsonPath.read(theirs, "$.record.actions[*].target.id");
    assertThat(landlordTargets).containsOnly(before.toString());
    List<String> landlordPhotoTargets = JsonPath.read(theirs, "$.record.photos[*].target.id");
    assertThat(landlordPhotoTargets).containsOnly(before.toString());
    assertThat((Integer) JsonPath.read(theirs, "$.photoCount")).isEqualTo(1);

    String mine = body(download(tenant).andExpect(status().isOk()));
    List<String> tenantTargets = JsonPath.read(mine, "$.record.actions[*].target.id");
    assertThat(tenantTargets).contains(before.toString(), after.toString());
    assertThat((Integer) JsonPath.read(mine, "$.photoCount")).isEqualTo(3);
  }

  @Test
  void 공유를_그만둔_상대가_탈퇴해도_그만둔_동안_남긴_기록은_들어가지_않는다() throws Exception {
    // 03 6-4: 중단 동안 탈퇴자가 남긴 기록은 상대가 본 적이 없어 숨기고 지운다. CONS-3q의 "풀린다"는 할 수 있는 일이다(#274 리뷰)
    UUID caseId = register(1);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN', sharing_stopped_at ="
            + " (SELECT created_at FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        caseId,
        room);

    String mine = body(download(tenant).andExpect(status().isOk()));
    List<String> types = JsonPath.read(mine, "$.record.actions[*].type");
    assertThat(types).containsExactly("REGISTER");
  }

  @Test
  void 내가_공유를_그만둔_동안_상대가_남기고_탈퇴하면_그_기록은_들어가지_않는다() throws Exception {
    // 03 6-4 "누가 공유를 그만뒀든 중단 동안 탈퇴자가 남긴 기록 전부". 내 제한이 풀려도(CONS-3p) 그 기록은 보이지 않는다
    UUID caseId = register(1);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at ="
            + " (SELECT created_at FROM state.repair_case WHERE id = ?)"
            + " WHERE room_record_id = ? AND side = 'TENANT'",
        caseId,
        room);
    respond(caseId, landlord, "ACK", null).andExpect(status().isOk());
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    String mine = body(download(tenant).andExpect(status().isOk()));
    List<String> types = JsonPath.read(mine, "$.record.actions[*].type");
    assertThat(types).containsExactly("REGISTER");
  }

  // ---- DL-1b 묶음 ----

  @Test
  void DL_1b_사진은_50장씩_묶음으로_나뉘고_묶음을_합치면_사진_목록과_같다() throws Exception {
    // 수리 건 하나에 10장까지라(06 REP-1) 6건 × 10장 = 60장
    for (int i = 0; i < 6; i++) {
      register(10);
    }

    String body = body(download(tenant).andExpect(status().isOk()));

    List<List<String>> batches = JsonPath.read(body, "$.photoBatches");
    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).hasSize(50);
    assertThat(batches.get(1)).hasSize(10);
    List<String> flattened = new ArrayList<>();
    batches.forEach(flattened::addAll);
    List<String> photoIds = JsonPath.read(body, "$.record.photos[*].photoId");
    assertThat(flattened).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(photoIds);
    assertThat((Integer) JsonPath.read(body, "$.photoCount")).isEqualTo(60);
  }

  @Test
  void 사진이_없으면_묶음도_없다() throws Exception {
    download(tenant)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.record.actions").isEmpty())
        .andExpect(jsonPath("$.record.photos").isEmpty())
        .andExpect(jsonPath("$.photoBatches").isEmpty())
        .andExpect(jsonPath("$.photoCount").value(0));
  }

  // ---- 받을 수 있는 사람(03 3장 권한 표 "내려받기", 6-3 "그동안") ----

  @Test
  void 정산이_끝난_호실도_내려받을_수_있다() throws Exception {
    // 03 6-3: 보관 기간 동안 양측 읽기 전용, 내려받기 가능
    register(1);
    jdbc.update(
        "UPDATE state.room_record SET status = 'SETTLED', ended_at = CURRENT_TIMESTAMP,"
            + " settled_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    download(landlord)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.record.roomStatus").value("SETTLED"))
        .andExpect(jsonPath("$.record.actions.length()").value(1));
  }

  @Test
  void 공유를_그만둔_사람도_내려받을_수_있다() throws Exception {
    // 03 B 공유 그만두기: 그만둔 사람은 기존 기록 보기·내려받기를 계속한다
    register(1);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = CURRENT_TIMESTAMP"
            + " WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    download(landlord).andExpect(status().isOk());
  }

  @Test
  void 없는_호실이나_그_호실_당사자가_아니면_NOT_FOUND() throws Exception {
    register(1);

    mvc.perform(get("/api/rooms/{id}/download", UUID.randomUUID()).with(loginAs(tenant)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    download(member())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void 로그인하지_않으면_LOGIN_REQUIRED() throws Exception {
    // 링크 열람자는 내려받을 수 없다(03 3장 권한 표 "내려받기" 링크 열람 ✕)
    mvc.perform(get("/api/rooms/{id}/download", room))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  // ---- 준비물 ----

  private ResultActions download(UUID memberId) throws Exception {
    return mvc.perform(get("/api/rooms/{id}/download", room).with(loginAs(memberId)));
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  /** 세입자가 사진 {@code photos}장으로 하자를 등록하고 수리 건 ID를 돌려준다. */
  private UUID register(int photos) throws Exception {
    StringBuilder tickets = new StringBuilder();
    for (int i = 0; i < photos; i++) {
      tickets.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    String body =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\"천장 누수\",\"description\":\"비 오면 물이 떨어져요\","
                            + "\"uploadTickets\":["
                            + tickets
                            + "]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private ResultActions respond(UUID caseId, UUID memberId, String answer, String reason)
      throws Exception {
    String json =
        "{\"answer\":\""
            + answer
            + "\""
            + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
            + ",\"seenVersionNo\":1}";
    return mvc.perform(
        post("/api/repair-cases/{id}/respond", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "res-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions supplement(UUID caseId, UUID memberId, String description)
      throws Exception {
    return mvc.perform(
        post("/api/repair-cases/{id}/supplement", caseId)
            .with(loginAs(memberId))
            .header("Idempotency-Key", "sup-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"" + description + "\"}"));
  }

  private UUID insertRoom() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id);
    return id;
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private void seat(PartySide side, UUID memberId) {
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, ?, ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        side.name(),
        memberId);
  }

  /** 기본 목록(V7)에서 복사된 "방 › 천장". 공유를 그만둔 뒤 하자 등록은 보이는 부위로 판단해(#183) 템플릿을 가리킨다. */
  private UUID part() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, t.id, t.zone, t.name, t.sort_order, CURRENT_TIMESTAMP, 0
        FROM state.part_template t WHERE t.zone = '방' AND t.name = '천장'
        """,
        id,
        room);
    return id;
  }
}
