package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.entity.ActionRecord;
import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * jsonb 칸을 엔티티의 String으로 저장해도 JSON 객체로 들어가는지 본다. ddl-auto=validate는 타입 이름만 보므로 따로 확인한다(05 2-5
 * "payload.answer로 조회").
 */
class JsonColumnEntityTest extends SchemaTestSupport {

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 행동의_payload는_JSON_객체로_저장되어_칸을_꺼낼_수_있다() {
    UUID room = room();
    ActionRecord action =
        new ActionRecord(
            room,
            1,
            new byte[32],
            new byte[32],
            null,
            null,
            null,
            "server",
            "AUTOCLOSE",
            "json-test",
            UUID.randomUUID(),
            null,
            null,
            "{\"answer\":\"DISPUTE\"}",
            Instant.parse("2026-11-17T00:00:00Z"));
    entityManager.persist(action);
    entityManager.flush();

    String answer =
        jdbc.queryForObject(
            "SELECT payload->>'answer' FROM action WHERE id = ?", String.class, action.getId());
    String type =
        jdbc.queryForObject(
            "SELECT jsonb_typeof(payload) FROM action WHERE id = ?", String.class, action.getId());

    assertThat(answer).isEqualTo("DISPUTE");
    assertThat(type).isEqualTo("object");
  }

  @Test
  void 고친_내용의_새_값은_JSON으로_저장된다() {
    UUID room = room();
    CorrectionRequest request =
        new CorrectionRequest(
            room,
            "move_in_date",
            "{\"value\":\"2026-03-01\"}",
            null,
            null,
            null,
            null,
            null,
            "MOVE_IN_DATE:" + room,
            null,
            PartySide.TENANT,
            "계약서 날짜",
            Instant.parse("2026-11-17T00:00:00Z"));
    entityManager.persist(request);
    entityManager.flush();

    String value =
        jdbc.queryForObject(
            "SELECT new_value->>'value' FROM correction_request WHERE id = ?",
            String.class,
            request.getId());

    assertThat(value).isEqualTo("2026-03-01");
  }
}
