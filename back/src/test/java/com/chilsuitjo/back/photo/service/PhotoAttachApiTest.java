package com.chilsuitjo.back.photo.service;

import static com.chilsuitjo.back.global.security.SecurityTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import com.chilsuitjo.back.photo.rule.PhotoTargetType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 사진 붙이기를 API부터 pipeline·실제 DB까지 띄워 본다(06 4-5 "사진은 따로 확정 API가 없다"). 사진 오류면 행동도 남지 않고(06 REP-1), 같은
 * 키로 다시 오면 S3를 다시 부르지 않는다(04 5-1 "멱등 키는 S3 작업보다 먼저").
 */
@AutoConfigureMockMvc
class PhotoAttachApiTest extends PhotoAttachTestSupport {

  @Autowired private MockMvc mvc;

  @Test
  void 하자를_등록하면_사진_카드와_행동_기록에_같은_지문이_남는다() throws Exception {
    Upload upload = upload();

    UUID caseId = created(register("with-photo", List.of(upload)));

    var row =
        jdbc.queryForMap(
            "SELECT id, stage, original_sha256 FROM record.photo WHERE repair_case_id = ?", caseId);
    assertThat(row.get("stage")).isEqualTo("BEFORE");
    assertThat(row.get("original_sha256")).isEqualTo(upload.originalSha256Hex());
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM record.action WHERE repair_case_id = ?",
            String.class,
            caseId);
    assertThat(payload).contains(row.get("id").toString(), upload.originalSha256Hex());
  }

  @Test
  void PHO_1a_위치_정보가_남은_사진이면_422와_사진_번호를_주고_수리_건도_남지_않는다() throws Exception {
    register("gps", List.of(upload(), upload(TestJpegs.withGps(), TestJpegs.redrawn())))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("PHOTO_CHECK_FAILED"))
        .andExpect(jsonPath("$.photoIndex").value(1));

    // 준비에서 SQL로 만든 수리 건 하나뿐이다
    assertThat(count("SELECT count(*) FROM state.repair_case WHERE room_record_id = ?")).isOne();
    assertThat(count("SELECT count(*) FROM record.action WHERE room_record_id = ?")).isZero();
    assertThat(photoCount()).isZero();
  }

  @Test
  void 같은_키로_다시_오면_S3를_부르지_않고_처음_결과를_준다() throws Exception {
    Upload upload = upload();
    UUID first = created(register("same-key", List.of(upload)));
    int callsAfterFirst = store.calls().size();

    UUID again = created(register("same-key", List.of(upload)));

    assertThat(again).isEqualTo(first);
    assertThat(store.calls()).hasSize(callsAfterFirst);
    assertThat(photoCount()).isOne();
  }

  @Test
  void 수리_건_한도를_넘는_진행_상황_사진은_PHOTO_LIMIT이고_사진_번호가_없다() throws Exception {
    UUID caseId = created(register("full-case", List.of(upload())));
    var target = new PhotoTarget.Ref(PhotoTargetType.REPAIR_CASE, caseId);
    for (int i = 0; i < REPAIR_LIMIT - 1; i++) {
      existingPhoto(target, PartySide.TENANT, NOW.minusSeconds(60), false);
    }

    mvc.perform(
            post("/api/repair-cases/{id}/progress-notes", caseId)
                .with(loginAs(tenant))
                .header("Idempotency-Key", "note-over-limit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"사진 더\",\"uploadTickets\":[\"" + upload().ticket() + "\"]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("PHOTO_LIMIT"))
        .andExpect(jsonPath("$.photoIndex").doesNotExist());
  }

  private ResultActions register(String key, List<Upload> uploads) throws Exception {
    String tickets =
        String.join(",", uploads.stream().map(upload -> "\"" + upload.ticket() + "\"").toList());
    return mvc.perform(
        post("/api/rooms/{id}/repair-cases", room)
            .with(loginAs(tenant))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"roomPartId\":\""
                    + part
                    + "\",\"title\":\"누수\",\"uploadTickets\":["
                    + tickets
                    + "]}"));
  }

  private static UUID created(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class, room);
  }
}
