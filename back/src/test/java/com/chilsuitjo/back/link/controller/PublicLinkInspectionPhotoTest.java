package com.chilsuitjo.back.link.controller;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.link.rule.LinkToken;
import com.chilsuitjo.back.photo.repository.PhotoObjectStore;
import com.chilsuitjo.back.photo.service.JpaPhotoService;
import com.chilsuitjo.back.photo.service.PhotoService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 점검 링크의 사진을 실제 사진 구현({@code photo.service=jpa}, {@link JpaPhotoService})과 DB 사진 행으로 본다(#303). 기본
 * 설정은 메모리 가짜({@code FakePhotoService})라 DB 사진을 읽지 않으므로 따로 둔다. 링크용 사진은 서명 주소만 만들고 파일을 읽지 않아, 저장소는
 * 부르면 실패하는 것으로 둔다(버킷 없이 뜨는 테스트 설정).
 */
@SpringBootTest(properties = "photo.service=jpa")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PublicLinkInspectionPhotoTest.NoStore.class})
@ActiveProfiles("test")
class PublicLinkInspectionPhotoTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhotoService photos;

  private UUID room;
  private UUID tenant;
  private UUID landlordSeat;
  private UUID part;

  @BeforeEach
  void setUp() {
    // 이 테스트만 쓰는 지역 코드. 시·도 목록(RegionApiTest)은 코드 앞 두 자리마다 이름이 하나여야 해서 다른 99 지역과 같은 이름을 쓴다
    jdbc.update(
        "INSERT INTO state.region (code, sido, dong) VALUES ('9999999972', '테스트', '사진동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO state.room_record
          (id, region_code, alias, move_in_date, status, created_by_side, created_at, version)
        VALUES (?, '9999999972', '별칭', DATE '2026-03-01', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
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
        INSERT INTO state.party (id, room_record_id, side, invite_phone, status, created_at, version)
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
  void 실제_사진_구현이_쓰인다() {
    assertThat(photos).isInstanceOf(JpaPhotoService.class);
  }

  @Test
  void DB에_있는_항목_사진은_그_항목에_5분_주소로_보이고_링크에서_숨긴_사진은_빠지고_지운_사진은_흔적만_보인다() throws Exception {
    UUID inspection = requestedInspection();
    UUID item =
        jdbc.queryForObject(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ?", UUID.class, inspection);
    UUID shown = photo(item, 0, false, false);
    UUID hidden = photo(item, 1, true, false);
    UUID erased = photo(item, 2, false, true);

    String body =
        view(link(inspection))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.record.items[0].photos.length()").value(2))
            .andExpect(jsonPath("$.record.items[0].photos[0].id").value(shown.toString()))
            .andExpect(jsonPath("$.record.items[0].photos[0].erased").value(false))
            .andExpect(jsonPath("$.record.items[0].photos[0].thumbUrl").isNotEmpty())
            .andExpect(jsonPath("$.record.items[0].photos[0].expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.record.items[0].photos[1].id").value(erased.toString()))
            .andExpect(jsonPath("$.record.items[0].photos[1].erased").value(true))
            .andExpect(jsonPath("$.record.items[0].photos[1].erasedSide").value("TENANT"))
            .andExpect(jsonPath("$.record.items[0].photos[1].erasedReason").value("PRIVACY"))
            .andExpect(jsonPath("$.record.items[0].photos[1].erasedAt").isNotEmpty())
            .andExpect(jsonPath("$.record.items[0].photos[1].thumbUrl").doesNotExist())
            .andExpect(jsonPath("$.record.items[0].photos[1].originalUrl").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(hidden.toString()).doesNotContain(item.toString());
  }

  @Test
  void 다른_사람의_요청으로_지운_사진은_측_없이_사유와_시각만_보인다() throws Exception {
    // 03 6-1·불변식 16: 본인이 지운 사진(측·시각)과 운영자가 다른 사람의 요청으로 지운 사진(측 없음)을 링크 화면이 구분한다
    UUID inspection = requestedInspection();
    UUID item =
        jdbc.queryForObject(
            "SELECT id FROM state.inspection_item WHERE inspection_id = ?", UUID.class, inspection);
    UUID mine = photo(item, 0, false, "PRIVACY");
    UUID requested = photo(item, 1, false, "THIRD_PARTY");

    view(link(inspection))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.record.items[0].photos[0].id").value(mine.toString()))
        .andExpect(jsonPath("$.record.items[0].photos[0].erasedSide").value("TENANT"))
        .andExpect(jsonPath("$.record.items[0].photos[0].erasedReason").value("PRIVACY"))
        .andExpect(jsonPath("$.record.items[0].photos[1].id").value(requested.toString()))
        .andExpect(jsonPath("$.record.items[0].photos[1].erased").value(true))
        .andExpect(jsonPath("$.record.items[0].photos[1].erasedSide").doesNotExist())
        .andExpect(jsonPath("$.record.items[0].photos[1].erasedReason").value("THIRD_PARTY"))
        .andExpect(jsonPath("$.record.items[0].photos[1].erasedAt").isNotEmpty())
        .andExpect(jsonPath("$.record.items[0].photos[1].thumbUrl").doesNotExist());
  }

  @Test
  void 사진이_없는_항목은_빈_목록이다() throws Exception {
    view(link(requestedInspection()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.record.items[0].photos").isEmpty());
  }

  // ---- 도우미 ----

  private UUID photo(UUID item, int sortOrder, boolean hiddenOnLink, boolean erased) {
    return photo(item, sortOrder, hiddenOnLink, erased ? "PRIVACY" : null);
  }

  private UUID photo(UUID item, int sortOrder, boolean hiddenOnLink, String eraseReason) {
    boolean erased = eraseReason != null;
    UUID id = UUID.randomUUID();
    Instant at = Instant.now().minusSeconds(60);
    jdbc.update(
        "INSERT INTO record.photo (id, room_record_id, inspection_item_id, stage, uploaded_by_side,"
            + " original_key, thumb_key, original_sha256, thumb_sha256, size_bytes, sort_order,"
            + " created_at) VALUES (?, ?, ?, 'BEFORE', 'TENANT', ?, ?, ?, ?, 1, ?, ?)",
        id,
        room,
        item,
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

  private UUID requestedInspection() throws Exception {
    UUID inspection =
        created(
            mvc.perform(
                post("/api/rooms/{id}/inspections", room)
                    .with(loginAs(tenant))
                    .header("Idempotency-Key", "start")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"kind\":\"MOVE_IN\"}")));
    created(
        mvc.perform(
            post("/api/inspections/{id}/items", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "item")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"roomPartId\":\""
                        + part
                        + "\",\"description\":\"수전에서 물이 새요\",\"uploadTickets\":[]}")));
    mvc.perform(
            post("/api/inspections/{id}/request-confirm", inspection)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "request"))
        .andExpect(status().isOk());
    return inspection;
  }

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

  private ResultActions view(String token) throws Exception {
    return mvc.perform(
        post("/api/public/links/view")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  /** 버킷 없이 뜨는 설정에는 저장소 빈이 없다. 링크용 사진은 파일을 읽지 않으므로 부르면 실패하게 둔다. */
  @TestConfiguration
  static class NoStore {
    @Bean
    PhotoObjectStore unusedPhotoObjectStore() {
      return new PhotoObjectStore() {
        @Override
        public Optional<ObjectHead> head(String key) {
          throw new UnsupportedOperationException("링크 사진은 파일을 읽지 않아요");
        }

        @Override
        public byte[] readStart(String key, String eTag, int bytes) {
          throw new UnsupportedOperationException("링크 사진은 파일을 읽지 않아요");
        }

        @Override
        public void copy(String sourceKey, String targetKey, String eTag) {
          throw new UnsupportedOperationException("링크 사진은 파일을 읽지 않아요");
        }

        @Override
        public void delete(String key) {
          throw new UnsupportedOperationException("링크 사진은 파일을 읽지 않아요");
        }
      };
    }
  }
}
