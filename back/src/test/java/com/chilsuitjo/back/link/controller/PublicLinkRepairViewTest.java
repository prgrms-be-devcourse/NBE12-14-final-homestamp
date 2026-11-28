package com.chilsuitjo.back.link.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.link.rule.LinkToken;
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
 * 수리 건을 공유한 링크를 실제 경로로 연다(06 LINK-1a, #303·#447): 공개 API → {@code LinkViewService} → 운영 {@code
 * LinkContents} → room·repair·photo의 공개 조회. 수리 건은 수리 API로 등록하고(세입자), 링크는 아직 연결하지 않은 집주인 자리로 보낸 것이다.
 * 보이는 범위의 세부는 repair 테스트(#447)가 보고, 여기서는 연결과 응답 모양, 막는 경우를 본다. 사진 행은 {@link
 * PublicLinkRepairPhotoTest}가 실제 사진 구현으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicLinkRepairViewTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID room;
  private UUID tenant;
  private UUID tenantSeat;
  private UUID landlordSeat;
  private UUID part;

  @BeforeEach
  void setUp() {
    // 이 테스트만 쓰는 지역 코드. 시·도 목록(RegionApiTest)은 코드 앞 두 자리마다 이름이 하나여야 해서 다른 99 지역과 같은 이름을 쓴다
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES ('9999999973', '테스트', '강남구', '수리동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999973', '비밀 별칭', DATE '2026-03-01', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = member();
    tenantSeat = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, member_id, status, connected_at, created_at, version)
        VALUES (?, ?, 'TENANT', ?, 'CONNECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
        """,
        tenantSeat,
        room,
        tenant);
    landlordSeat = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.party
          (id, room_record_id, side, invite_phone, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', '01077778888', 'INVITED', CURRENT_TIMESTAMP, 0)
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
  void 수리_건을_공유한_링크는_부위_제목_설명_상태_경과를_보이고_항목과_ID_이름은_없다() throws Exception {
    UUID repair = register();
    UUID cause = causeNote(repair);
    String token = link(repair);
    UUID notice = sentNotice(token);

    String body =
        view(token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("RECORD"))
            .andExpect(jsonPath("$.sido").value("테스트"))
            .andExpect(jsonPath("$.dong").value("수리동"))
            .andExpect(jsonPath("$.yourSide").value("LANDLORD"))
            // 돈 기록이 있는지는 payment(#340 전 가짜, 늘 false)
            .andExpect(jsonPath("$.hasMoney").value(false))
            .andExpect(jsonPath("$.record.targetKind").value("REPAIR_CASE"))
            .andExpect(jsonPath("$.record.partName").value("세면대"))
            .andExpect(jsonPath("$.record.title").value("수전에서 물이 새요"))
            .andExpect(jsonPath("$.record.description").value("어제부터 새요"))
            .andExpect(jsonPath("$.record.statusLines[0]").value("하자 · 확인 기다림"))
            .andExpect(jsonPath("$.record.statusLines[1]").value("수리 · 시작 전"))
            .andExpect(jsonPath("$.record.items").isEmpty())
            .andExpect(jsonPath("$.record.history[0].label").value("세입자가 하자를 등록했어요"))
            .andExpect(jsonPath("$.record.history[0].side").value("TENANT"))
            .andExpect(jsonPath("$.record.history[1].label").value("세입자가 원인 설명을 남겼어요"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 수리 건·원인 설명·호실·부위·회원 ID, 별칭, 번호, 전문가 이름, 원인 설명 글은 응답 어디에도 없다(LINK-1a·2a)
    assertThat(body)
        .doesNotContain(repair.toString())
        .doesNotContain(cause.toString())
        .doesNotContain(room.toString())
        .doesNotContain(part.toString())
        .doesNotContain(tenant.toString())
        .doesNotContain("비밀 별칭")
        .doesNotContain("01077778888")
        .doesNotContain("김배관")
        .doesNotContain("낡은 수전");
    assertThat(linkViews()).isEqualTo(1);
    // 기록 종류와 상관없는 공통 경로(#433): 그 링크로 보낸 SENT 문자는 열람이 전달 증거가 된다
    assertThat(noticeStatus(notice)).isEqualTo("VIEWED");
  }

  @Test
  void 무효가_된_수리_건_링크는_LINK_INVALID이고_열람_기록이_없다() throws Exception {
    String token = link(register());
    UUID notice = sentNotice(token);
    jdbc.update(
        "UPDATE state.link SET invalidated_at = CURRENT_TIMESTAMP, invalidated_reason = 'REINVITED'"
            + " WHERE token_hash = ?",
        LinkToken.hash(token));

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    assertThat(linkViews()).isZero();
    assertThat(noticeStatus(notice)).isEqualTo("SENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"REJECTED", "CONNECTED", "EMPTY"})
  void 받는_자리가_초대됨이_아니면_수리_건_링크는_LINK_INVALID이고_열람_기록이_없다(String seat) throws Exception {
    String token = link(register());
    jdbc.update(
        "UPDATE state.party SET status = ?, member_id = ?, invite_phone = NULL WHERE id = ?",
        seat,
        seat.equals("CONNECTED") ? member() : null,
        landlordSeat);

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    assertThat(linkViews()).isZero();
  }

  @Test
  void 거주_종료된_호실의_수리_건_링크는_LINK_INVALID이고_열람_기록이_없다() throws Exception {
    String token = link(register());
    jdbc.update(
        "UPDATE state.room_record SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?",
        room);

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    assertThat(linkViews()).isZero();
  }

  @Test
  void 공유를_그만둔_뒤_등록한_수리_건은_상대에게_보내도_열리지_않고_열람_기록이_없다() throws Exception {
    // 불변식 18: 공유 중단 뒤 남긴 기록은 상대에게 보이지 않는다(repair가 빈 값 → link는 LINK_INVALID)
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
        tenantSeat);
    String token = link(register());

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    assertThat(linkViews()).isZero();
  }

  @Test
  void 보낸_측이_등록_뒤_공유를_그만두면_그_링크는_LINK_INVALID이고_열람_기록과_전달_증거가_없다() throws Exception {
    // 06 LINK-4a, #475: 공유 그만두기가 링크를 무효화하는 저장(CONS-3)은 아직 없다. 그 전에도 열람 쪽에서 막히는지 본다.
    // 공유 그만두기 API가 생기면 그 PR에서 행동 → 열람 흐름 전체를 다시 본다(여기서는 자리 상태만 바꿈)
    String token = link(register());
    UUID notice = sentNotice(token);
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED',"
            + " sharing_stopped_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
        tenantSeat);

    view(token)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LINK_INVALID"));
    assertThat(linkViews()).isZero();
    // 막힌 열람은 전달 증거도 남기지 않는다
    assertThat(noticeStatus(notice)).isEqualTo("SENT");
    // 다시 공유해도 그 링크가 계속 닫혀 있는 것(LINK-4b, 불변식 7)은 공유 그만두기의 링크 무효화(SHARING_STOPPED)가 맡는다
  }

  // ---- 도우미 ----

  private UUID register() throws Exception {
    String body =
        mvc.perform(
                post("/api/rooms/{id}/repair-cases", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"roomPartId\":\""
                            + part
                            + "\",\"title\":\"수전에서 물이 새요\",\"description\":\"어제부터 새요\","
                            + "\"uploadTickets\":[\""
                            + UUID.randomUUID()
                            + "\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return id(body);
  }

  private UUID causeNote(UUID repair) throws Exception {
    String body =
        mvc.perform(
                post("/api/repair-cases/{id}/cause-notes", repair)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "cause-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"text\":\"낡은 수전\",\"basis\":\"EXPERT\",\"expertName\":\"김배관 설비\","
                            + "\"uploadTickets\":[]}"))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return id(body);
  }

  private static UUID id(String body) {
    return UUID.fromString(body.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"));
  }

  private String link(UUID repair) {
    String token = LinkToken.newToken();
    jdbc.update(
        "INSERT INTO state.link (id, room_record_id, party_id, issued_by_member_id, kind,"
            + " repair_case_id, token_hash, created_at, version)"
            + " VALUES (?, ?, ?, ?, 'INVITE', ?, ?, CURRENT_TIMESTAMP, 0)",
        UUID.randomUUID(),
        room,
        landlordSeat,
        tenant,
        repair,
        LinkToken.hash(token));
    return token;
  }

  /** 그 링크로 이미 보낸, 아직 전달 증거가 없는 초대 문자(요청 없음). */
  private UUID sentNotice(String token) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.notice (id, room_record_id, link_id, recipient_party_id, kind, channel,
          status, sent_at, next_poll_at, delivery_check_due_at, created_at, version)
        SELECT ?, room_record_id, id, party_id, 'INVITE', 'SMS', 'SENT',
          CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 minute',
          CURRENT_TIMESTAMP + INTERVAL '3 days', CURRENT_TIMESTAMP - INTERVAL '2 minutes', 0
        FROM state.link WHERE token_hash = ?
        """,
        id,
        LinkToken.hash(token));
    return id;
  }

  private String noticeStatus(UUID id) {
    return jdbc.queryForObject("SELECT status FROM state.notice WHERE id = ?", String.class, id);
  }

  private ResultActions view(String token) throws Exception {
    return mvc.perform(
        post("/api/public/links/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
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
}
