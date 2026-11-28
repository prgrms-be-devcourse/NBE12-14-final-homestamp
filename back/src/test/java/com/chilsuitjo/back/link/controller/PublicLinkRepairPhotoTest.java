package com.chilsuitjo.back.link.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.link.rule.LinkToken;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수리 건 링크의 사진을 실제 사진 구현({@code photo.service=jpa})과 DB 사진 행으로 본다(06 4-4, 03 6-1, 불변식 16). 수리 건은 사진
 * 붙이기 테스트({@code PhotoAttachTestSupport})와 같이 행으로 만든다(실제 업로드 표 없이). 저장소는 점검 사진 링크 테스트와 같이 부르면 실패한다.
 */
@SpringBootTest(properties = "photo.service=jpa")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PublicLinkInspectionPhotoTest.NoStore.class})
@ActiveProfiles("test")
class PublicLinkRepairPhotoTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transaction;

  private UUID room;
  private UUID tenant;
  private UUID landlordSeat;
  private UUID repair;

  @BeforeEach
  void setUp() {
    // 시·도 목록(RegionApiTest)은 코드 앞 두 자리마다 이름이 하나여야 해서 다른 99 지역과 같은 이름을 쓴다
    jdbc.update(
        "INSERT INTO state.region (code, sido, sigungu, dong) VALUES ('9999999973', '테스트', '강남구', '수리동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999973', '별칭', DATE '2026-03-01', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    tenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        tenant);
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
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '욕실', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        room);
    repair = UUID.randomUUID();
    // 지금 판 FK는 지연 검사라 수리 건과 판을 한 트랜잭션에 넣는다(PhotoAttachTestSupport와 같음)
    transaction.executeWithoutResult(status -> insertRepairCase(part));
  }

  private void insertRepairCase(UUID part) {
    UUID content = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.repair_case (id, room_record_id, current_content_id, status, defect_ack,
          money_status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        repair,
        room,
        content);
    jdbc.update(
        """
        INSERT INTO record.repair_content (id, room_record_id, repair_case_id, no, room_part_id,
          title, created_by_side, created_at)
        VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
        """,
        content,
        room,
        repair,
        part);
  }

  @Test
  void 수리_건_사진은_5분_주소로_보이고_링크에서_숨긴_사진은_빠지고_지운_사진은_흔적만_보인다() throws Exception {
    UUID shown = photo(0, false, null);
    UUID hidden = photo(1, true, null);
    UUID mine = photo(2, false, "PRIVACY");
    UUID third = photo(3, false, "THIRD_PARTY");

    String body =
        view(link())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.record.targetKind").value("REPAIR_CASE"))
            .andExpect(jsonPath("$.record.title").value("누수"))
            .andExpect(jsonPath("$.record.items").isEmpty())
            .andExpect(jsonPath("$.record.photos.length()").value(3))
            .andExpect(jsonPath("$.record.photos[0].id").value(shown.toString()))
            .andExpect(jsonPath("$.record.photos[0].erased").value(false))
            .andExpect(jsonPath("$.record.photos[0].thumbUrl").isNotEmpty())
            .andExpect(jsonPath("$.record.photos[0].expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.record.photos[1].id").value(mine.toString()))
            .andExpect(jsonPath("$.record.photos[1].erased").value(true))
            .andExpect(jsonPath("$.record.photos[1].thumbUrl").doesNotExist())
            .andExpect(jsonPath("$.record.photos[1].erasedSide").value("TENANT"))
            .andExpect(jsonPath("$.record.photos[1].erasedReason").value("PRIVACY"))
            .andExpect(jsonPath("$.record.photos[2].id").value(third.toString()))
            .andExpect(jsonPath("$.record.photos[2].erasedSide").doesNotExist())
            .andExpect(jsonPath("$.record.photos[2].erasedReason").value("THIRD_PARTY"))
            .andExpect(jsonPath("$.record.photos[2].erasedAt").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(hidden.toString()).doesNotContain(repair.toString());
  }

  private UUID photo(int sortOrder, boolean hiddenOnLink, String eraseReason) {
    boolean erased = eraseReason != null;
    UUID id = UUID.randomUUID();
    Instant at = Instant.now().minusSeconds(60);
    jdbc.update(
        "INSERT INTO record.photo (id, room_record_id, repair_case_id, stage, uploaded_by_side,"
            + " original_key, thumb_key, original_sha256, thumb_sha256, size_bytes, sort_order,"
            + " created_at) VALUES (?, ?, ?, 'BEFORE', 'TENANT', ?, ?, ?, ?, 1, ?, ?)",
        id,
        room,
        repair,
        "photos/" + room + "/" + id + "/original.jpg",
        "photos/" + room + "/" + id + "/thumb.jpg",
        "0".repeat(64),
        "0".repeat(64),
        sortOrder,
        Timestamp.from(at));
    jdbc.update(
        "INSERT INTO state.photo_visibility (photo_id, room_record_id, hidden_on_link, erased_at,"
            + " erase_reason, created_at, version) VALUES (?, ?, ?, ?, ?, ?, 0)",
        id,
        room,
        hiddenOnLink,
        erased ? Timestamp.from(at.plusSeconds(1)) : null,
        eraseReason,
        Timestamp.from(at));
    return id;
  }

  private String link() {
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

  private ResultActions view(String token) throws Exception {
    return mvc.perform(
        post("/api/public/links/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
  }
}
