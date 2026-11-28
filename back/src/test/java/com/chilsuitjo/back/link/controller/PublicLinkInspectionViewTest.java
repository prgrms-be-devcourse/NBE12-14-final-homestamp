package com.chilsuitjo.back.link.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.link.service.LinkContentSource;
import com.chilsuitjo.back.link.service.LinkContents;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 점검을 공유한 링크를 실제 경로로 연다(#303): 공개 API → {@code LinkViewService} → 운영 {@link LinkContents} →
 * room·inspection·photo의 공개 조회. 점검은 점검 API로 만들고(세입자가 쓰고 확인 요청), 링크는 아직 연결하지 않은 집주인 자리로 보낸 것이다. 보이는
 * 범위의 세부(공유 중단 기준 시각, 불변식 18)는 inspection 테스트(#397)가 보고, 여기서는 연결과 응답 모양을 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicLinkInspectionViewTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LinkContentSource content;

  private UUID room;
  private UUID tenant;
  private UUID landlordSeat;
  private UUID part;

  @BeforeEach
  void setUp() {
    // 시·도 목록(RegionApiTest)은 코드 앞 두 자리마다 이름이 하나여야 해서, 다른 테스트의 99 지역과 같은 시·도 이름을 쓴다
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES ('9999999971', '테스트', '강남구', '링크동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999971', '비밀 별칭', DATE '2026-03-01', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = member();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        UUID.randomUUID(),
        room,
        tenant);
    landlordSeat = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', '01055556666', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        landlordSeat,
        room);
    part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part
          (id, room_record_id, template_id, zone, name, sort_order, created_at, version)
        SELECT ?, ?, (SELECT id FROM state.part_template ORDER BY sort_order LIMIT 1), '욕실', '세면대', 0,
               CURRENT_TIMESTAMP, 0
        """,
        part,
        room);
  }

  @Test
  void 운영_구현이_쓰인다() {
    assertThat(content).isInstanceOf(LinkContents.class);
  }

  @Test
  void 확인_요청한_점검을_공유한_링크는_항목마다_부위_설명_상태_사진을_묶어_보이고_항목_ID는_없다() throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    requestConfirm(inspection);
    UUID item =
        jdbc.queryForObject(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ?", UUID.class, inspection);
    String token = link(inspection);

    String body =
        view(token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("RECORD"))
            .andExpect(jsonPath("$.sido").value("테스트"))
            .andExpect(jsonPath("$.dong").value("링크동"))
            .andExpect(jsonPath("$.yourSide").value("LANDLORD"))
            .andExpect(jsonPath("$.hasMoney").value(false))
            .andExpect(jsonPath("$.record.targetKind").value("INSPECTION"))
            .andExpect(jsonPath("$.record.title").value("입주 점검"))
            .andExpect(jsonPath("$.record.statusLines[0]").value("확인 요청"))
            .andExpect(jsonPath("$.record.photos").isEmpty())
            .andExpect(jsonPath("$.record.items.length()").value(1))
            .andExpect(jsonPath("$.record.items[0].partName").value("세면대"))
            .andExpect(jsonPath("$.record.items[0].description").value("수전에서 물이 새요"))
            .andExpect(jsonPath("$.record.items[0].statusLine").value("확인 기다림"))
            .andExpect(jsonPath("$.record.items[0].photos").isEmpty())
            .andExpect(jsonPath("$.record.history[0].label").value("세입자가 점검을 시작했어요"))
            .andExpect(jsonPath("$.record.history[1].label").value("세면대 · 세입자가 하자를 더했어요"))
            .andExpect(jsonPath("$.record.history[2].label").value("세입자가 확인을 요청했어요"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 항목·점검·호실·부위·회원 ID, 별칭, 번호는 응답 어디에도 없다(LINK-1a·2a, #303)
    assertThat(body)
        .doesNotContain(item.toString())
        .doesNotContain(inspection.toString())
        .doesNotContain(room.toString())
        .doesNotContain(part.toString())
        .doesNotContain(tenant.toString())
        .doesNotContain("비밀 별칭")
        .doesNotContain("01055556666");
    assertThat(linkViews()).isEqualTo(1);
  }

  @Test
  void 작성_중인_점검을_공유한_링크는_LINK_INVALID이고_열람_기록이_남지_않는다() throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    String token = link(inspection);

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));

    assertThat(linkViews()).isZero();
    assertThat(failedViews(token)).isEqualTo(1);
  }

  @Test
  void 무효가_된_점검_링크는_LINK_INVALID다() throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    requestConfirm(inspection);
    String token = link(inspection);
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE token_hash = ?",
        LinkToken.hash(token));

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));

    assertThat(linkViews()).isZero();
  }

  // ---- 링크 종류별 자리·호실 상태(03 3장 상태별 표 "링크", 03 C, 거절됨은 #392 7222021503, #358 RoomForLink는 상태만 주고 판단은
  // link)
  // ----

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "CONNECTED", "EMPTY", "NUMBER_ONLY", "WITHDRAWN"})
  void 받는_자리가_초대됨이_아니면_점검_링크와_호실_초대_링크는_LINK_INVALID이고_열람_기록이_없다(String seat) throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    requestConfirm(inspection);
    String recordLink = link(inspection);
    String roomLink = link(null);
    seat(seat);

    view(recordLink)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    view(roomLink)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));

    assertThat(linkViews()).isZero();
    assertThat(failedViews(recordLink)).isEqualTo(1);
  }

  @Test
  void 정상으로_연_뒤_자리가_거절됨이_되면_최근_열람_안이어도_다시_열지_못하고_이전_열람_기록은_남는다() throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    requestConfirm(inspection);
    String recordLink = link(inspection);
    view(recordLink).andExpect(status().isOk());
    assertThat(linkViews()).isEqualTo(1);

    seat("REJECTED");

    // 최근 열람 생략(link.view.dedupe)보다 자리 상태 판단이 먼저다
    String body =
        view(recordLink)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LINK_INVALID"))
            .andExpect(jsonPath("$.record").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("세면대");
    assertThat(linkViews()).isEqualTo(1);
    assertThat(failedViews(recordLink)).isEqualTo(1);
  }

  @Test
  void 거주_종료된_호실의_점검_링크와_호실_초대_링크는_LINK_INVALID이고_열람_기록이_없다() throws Exception {
    UUID inspection = started();
    created(addItem(inspection));
    requestConfirm(inspection);
    String recordLink = link(inspection);
    String roomLink = link(null);
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    view(recordLink).andExpect(status().isNotFound());
    view(roomLink).andExpect(status().isNotFound());

    assertThat(linkViews()).isZero();
  }

  @Test
  void 초대됨_자리와_거주_중_호실의_호실_초대_링크는_열린다() throws Exception {
    view(link(null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("ROOM_INVITE"))
        .andExpect(jsonPath("$.yourSide").value("LANDLORD"))
        .andExpect(jsonPath("$.record").doesNotExist());

    assertThat(linkViews()).isEqualTo(1);
  }

  @Test
  void 정산_초대_링크는_거주_종료된_호실에서도_초대_링크_조건_없이_열린다() throws Exception {
    // 거주 종료 뒤 연결되지 않은 상대에게 보내는 링크(03 C). 초대 링크의 "초대됨·거주 중" 조건을 쓰지 않는다
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);
    seat("NUMBER_ONLY");
    String token = LinkToken.newToken();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'SETTLEMENT_INVITE', ?, CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        room,
        landlordSeat,
        tenant,
        LinkToken.hash(token));

    view(token).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("SETTLEMENT_INVITE"));
  }

  // ---- 도우미 ----

  /** 집주인 자리를 그 상태로 바꾼다(연결·탈퇴 쪽은 회원을 붙인다, V1 CHECK). */
  private void seat(String state) {
    UUID member = state.equals("CONNECTED") ? member() : null;
    jdbc.update(
        "UPDATE state.party SET status = ?, member_id = ?, invite_phone = NULL WHERE id = ?",
        state,
        member,
        landlordSeat);
  }

  private ResultActions view(String token) throws Exception {
    return mvc.perform(
        post("/api/public/links/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
  }

  /** 집주인 자리로 보낸 초대 링크. {@code inspection}이 null이면 호실 초대 링크. */
  private String link(UUID inspection) {
    String token = LinkToken.newToken();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " inspection_id, token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'INVITE', ?, ?, CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        room,
        landlordSeat,
        tenant,
        inspection,
        LinkToken.hash(token));
    return token;
  }

  private UUID started() throws Exception {
    return created(
        mvc.perform(
            post("/api/rooms/{id}/inspections", room)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"MOVE_IN\"}")));
  }

  private ResultActions addItem(UUID inspection) throws Exception {
    return mvc.perform(
        post("/api/inspections/{id}/items", inspection)
            .with(loginAs(tenant))
            .header("Idempotency-Key", "item")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"description\":\"수전에서 물이 새요\",\"uploadTickets\":[]}"));
  }

  private void requestConfirm(UUID inspection) throws Exception {
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "request"))
        .andExpect(status().isOk());
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private int linkViews() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM record.action WHERE room_record_id = ? AND type = 'LINK_VIEW'",
        Integer.class,
        room);
  }

  private int failedViews(String token) {
    return jdbc.queryForObject(
        "SELECT failed_view_count FROM state.link WHERE token_hash = ?",
        Integer.class,
        LinkToken.hash(token));
  }
}
