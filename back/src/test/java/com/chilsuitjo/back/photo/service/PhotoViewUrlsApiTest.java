package com.chilsuitjo.back.photo.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.common.PartySide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 사진 주소 받기({@code POST /api/photos/view-urls}, 06 4-5·PHO-2·5·7). 가짜 서명기라 주소에 서명 시각·만료·캐시가 그대로 적힌다.
 * 시계는 {@link #NOW}(03:00:00.123456)로 고정이다.
 *
 * <p>공유 중단 경계는 내려받기 묶음(#274, 지금은 {@code ActionVisibility})과 같아야 한다. 묶음 ID가 여기서 거절되면 내려받기가
 * 실패한다(#246).
 */
@AutoConfigureMockMvc
class PhotoViewUrlsApiTest extends PhotoAttachTestSupport {

  private static final String FAKE = "https://fake-photo-storage.invalid/";
  private static final Instant STOPPED = Instant.parse("2026-11-20T00:00:00Z");

  @Autowired private MockMvc mvc;

  @Test
  void 회원_썸네일은_정각에_서명해_2시간_뒤에_끝나고_요청한_순서대로_준다() throws Exception {
    UUID first = photo(PartySide.TENANT, NOW.minusSeconds(60));
    UUID second = photo(PartySide.LANDLORD, NOW.minusSeconds(30));

    viewUrls(tenant, List.of(second, first), "THUMB")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].photoId").value(second.toString()))
        .andExpect(jsonPath("$.items[1].photoId").value(first.toString()))
        .andExpect(
            jsonPath("$.items[1].url")
                .value(
                    FAKE
                        + thumbKey(first)
                        + "?signedAt=2026-11-26T03:00:00Z&expiresAt=2026-11-26T05:00:00Z"
                        + "&cacheControl=private%2C+max-age%3D3600"))
        .andExpect(jsonPath("$.items[1].expiresAt").value("2026-11-26T05:00:00Z"))
        .andExpect(jsonPath("$.items[1].erased").doesNotExist());
  }

  @Test
  void 원본은_5분짜리_주소다() throws Exception {
    UUID id = photo(PartySide.TENANT, NOW.minusSeconds(60));

    viewUrls(tenant, List.of(id), "ORIGINAL")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].url")
                .value(
                    FAKE
                        + "photos/"
                        + room
                        + "/"
                        + id
                        + "/original.jpg?signedAt=2026-11-26T03:00:00Z&expiresAt=2026-11-26T03:05:00Z"))
        .andExpect(jsonPath("$.items[0].expiresAt").value("2026-11-26T03:05:00Z"));
  }

  @Test
  @Tag("invariant-16")
  void 지운_사진은_주소_대신_올린_측과_지운_시각을_준다() throws Exception {
    Instant created = NOW.minusSeconds(60);
    UUID erased = existingPhoto(caseTarget(), PartySide.LANDLORD, created, true);
    UUID kept = photo(PartySide.TENANT, created);

    viewUrls(tenant, List.of(erased, kept), "ORIGINAL")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].url").doesNotExist())
        .andExpect(jsonPath("$.items[0].expiresAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].erased.side").value("LANDLORD"))
        .andExpect(jsonPath("$.items[0].erased.at").value(created.plusSeconds(1).toString()))
        .andExpect(jsonPath("$.items[0].erased.reason").value("PRIVACY"))
        .andExpect(jsonPath("$.items[1].url").isNotEmpty());
  }

  @Test
  @Tag("invariant-16")
  void 다른_사람의_요청으로_지운_사진은_측_없이_시각만_준다() throws Exception {
    UUID erased = existingPhoto(caseTarget(), PartySide.LANDLORD, NOW.minusSeconds(60), true);
    jdbc.update(
        "UPDATE state.photo_visibility SET erase_reason = 'THIRD_PARTY' WHERE photo_id = ?",
        erased);

    viewUrls(tenant, List.of(erased), "THUMB")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].erased.side").doesNotExist())
        .andExpect(jsonPath("$.items[0].erased.reason").value("THIRD_PARTY"));
  }

  @Test
  @Tag("invariant-18")
  void 공유를_그만둔_뒤_상대가_올린_사진이_섞이면_전체가_NOT_FOUND다() throws Exception {
    UUID before = photo(PartySide.LANDLORD, STOPPED.minusSeconds(1));
    UUID after = photo(PartySide.LANDLORD, STOPPED.plusSeconds(1));
    stopSharing(PartySide.TENANT);

    viewUrls(tenant, List.of(before, after), "ORIGINAL")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @Tag("invariant-18")
  void 그만두기_전_상대_사진과_그만둔_시각의_사진과_그만둔_뒤_내_사진은_받는다() throws Exception {
    UUID before = photo(PartySide.LANDLORD, STOPPED.minusSeconds(1));
    UUID atStop = photo(PartySide.LANDLORD, STOPPED);
    UUID mine = photo(PartySide.TENANT, STOPPED.plusSeconds(1));
    stopSharing(PartySide.LANDLORD);

    viewUrls(tenant, List.of(before, atStop, mine), "ORIGINAL")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3));
  }

  @Test
  void 탈퇴한_상대가_공유를_그만둔_동안_올린_사진은_NOT_FOUND다() throws Exception {
    UUID during = photo(PartySide.LANDLORD, STOPPED.plusSeconds(1));
    stopSharing(PartySide.LANDLORD);
    // 탈퇴해도 공유 중단 시각은 남는다(내려받기 cutoff.departed와 같은 기준)
    jdbc.update(
        "UPDATE state.party SET status = 'WITHDRAWN' WHERE room_record_id = ? AND side = 'LANDLORD'",
        room);

    viewUrls(tenant, List.of(during), "ORIGINAL").andExpect(status().isNotFound());
  }

  @Test
  void 없는_사진이_섞이면_NOT_FOUND다() throws Exception {
    UUID id = photo(PartySide.TENANT, NOW.minusSeconds(60));

    viewUrls(tenant, List.of(id, UUID.randomUUID()), "THUMB").andExpect(status().isNotFound());
  }

  @Test
  void 호실_회원이_아니면_NOT_FOUND다() throws Exception {
    UUID id = photo(PartySide.TENANT, NOW.minusSeconds(60));

    viewUrls(stranger(), List.of(id), "THUMB").andExpect(status().isNotFound());
  }

  @Test
  void 두_호실의_사진이_섞이면_둘_다_회원이어도_NOT_FOUND다() throws Exception {
    UUID firstRoomPhoto = photo(PartySide.TENANT, NOW.minusSeconds(60));
    UUID me = tenant;
    createsRoomWithRepairCase();
    UUID secondRoomPhoto = photo(PartySide.TENANT, NOW.minusSeconds(60));
    // 두 번째 호실에서는 내가 집주인 자리다
    jdbc.update(
        "UPDATE state.party SET member_id = ? WHERE room_record_id = ? AND side = 'LANDLORD'",
        me,
        room);

    viewUrls(me, List.of(secondRoomPhoto), "THUMB").andExpect(status().isOk());
    viewUrls(me, List.of(firstRoomPhoto, secondRoomPhoto), "THUMB")
        .andExpect(status().isNotFound());
  }

  @Test
  void 사진은_1장에서_50장까지이고_같은_ID는_두_번_올_수_없다() throws Exception {
    UUID id = photo(PartySide.TENANT, NOW.minusSeconds(60));
    List<UUID> fiftyOne = new ArrayList<>();
    for (int i = 0; i < 51; i++) {
      fiftyOne.add(UUID.randomUUID());
    }

    viewUrls(tenant, List.of(), "THUMB").andExpect(status().isBadRequest());
    viewUrls(tenant, fiftyOne, "THUMB")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    viewUrls(tenant, List.of(id, id), "THUMB")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    viewUrls(tenant, List.of(id), null).andExpect(status().isBadRequest());
  }

  @Test
  void 묶음_50장을_한_번에_받는다() throws Exception {
    List<UUID> ids = new ArrayList<>(existingPhotos(50));
    Collections.reverse(ids);

    viewUrls(tenant, ids, "ORIGINAL")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(50))
        .andExpect(jsonPath("$.items[0].photoId").value(ids.getFirst().toString()));
  }

  @Test
  void 로그인하지_않으면_받을_수_없다() throws Exception {
    UUID id = photo(PartySide.TENANT, NOW.minusSeconds(60));

    mvc.perform(json(post("/api/photos/view-urls"), List.of(id), "THUMB"))
        .andExpect(status().isUnauthorized());
  }

  // ---- 준비 ----

  private UUID photo(PartySide side, Instant createdAt) {
    return existingPhoto(caseTarget(), side, createdAt, false);
  }

  private String thumbKey(UUID id) {
    return "photos/" + room + "/" + id + "/thumb.jpg";
  }

  private void stopSharing(PartySide side) {
    jdbc.update(
        "UPDATE state.party SET status = 'SHARING_STOPPED', sharing_stopped_at = ?"
            + " WHERE room_record_id = ? AND side = ?",
        Timestamp.from(STOPPED),
        room,
        side.name());
  }

  private UUID stranger() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO state.member (id, status, created_at, version)"
            + " VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        id);
    return id;
  }

  private ResultActions viewUrls(UUID memberId, List<UUID> ids, String variant) throws Exception {
    return mvc.perform(json(post("/api/photos/view-urls").with(loginAs(memberId)), ids, variant));
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, List<UUID> ids, String variant) {
    String photoIds = String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList());
    String body =
        "{\"photoIds\":["
            + photoIds
            + "]"
            + (variant == null ? "" : ",\"variant\":\"" + variant + "\"")
            + "}";
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
