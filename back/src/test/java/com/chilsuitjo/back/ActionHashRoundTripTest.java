package com.chilsuitjo.back;

import static org.assertj.core.api.Assertions.assertThat;

import com.chilsuitjo.back.action.entity.ActionRecord;
import com.chilsuitjo.back.action.rule.ActionHash;
import com.chilsuitjo.back.global.common.PartySide;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL에 저장했다 읽은 값으로 다시 계산해도 같은 해시가 나오는지 본다(#72). jsonb는 키 순서·공백을 바꾸고, timestamptz는 마이크로초보다 작은
 * 자리를 반올림한다.
 */
class ActionHashRoundTripTest extends SchemaTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @PersistenceContext private EntityManager entityManager;

  @Test
  void 저장했다_읽은_행동으로_다시_계산해도_같은_해시() {
    UUID room = room();
    UUID repair = repair(room);
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    // 일부러 공백과 키 순서를 jsonb가 바꿀 모양으로 쓴다.
    String payloadJson =
        "{ \"while_open_objection\" : \"NEED_MORE\", \"b\": {\"y\": 2, \"x\": [1, \"a\", null]},"
            + " \"a\": true }";
    ActionHash.Input input =
        new ActionHash.Input(
            ActionHash.VERSION,
            room,
            1,
            ActionHash.start(),
            new byte[32],
            null, // 링크 행동: 회원 칸 없이 측만 있다
            PartySide.TENANT,
            "link:" + UUID.randomUUID(),
            "REGISTER",
            "round-trip",
            2,
            "사유",
            JSON.readValue(payloadJson, Object.class),
            "repair_case_id",
            repair,
            at,
            UUID.randomUUID());
    byte[] hash = ActionHash.compute(input);

    UUID id = save(input, hash, payloadJson).getId();
    entityManager.flush();
    entityManager.clear();

    ActionRecord stored = entityManager.find(ActionRecord.class, id);
    assertThat(stored.getPayload()).isNotEqualTo(payloadJson); // jsonb가 모양을 바꿨다
    assertThat(ActionHash.compute(readBack(stored))).isEqualTo(stored.getHash()).isEqualTo(hash);
  }

  @Test
  void 마이크로초로_자르지_않은_시각은_저장하면_값이_바뀐다() {
    UUID room = room();
    Instant withNanos = Instant.parse("2026-11-18T05:00:00.123456789Z");
    ActionRecord action =
        new ActionRecord(
            ActionHash.VERSION,
            room,
            1,
            ActionHash.start(),
            new byte[32],
            null,
            null,
            null,
            "server",
            "AUTOCLOSE",
            "nanos",
            UUID.randomUUID(),
            null,
            null,
            null,
            withNanos);
    entityManager.persist(action);
    entityManager.flush();
    entityManager.clear();

    Instant stored = entityManager.find(ActionRecord.class, action.getId()).getCreatedAt();
    // 그래서 ActionHash는 자르지 않은 시각을 거절하고, 자른 같은 값을 계산과 저장에 쓴다.
    assertThat(stored).isNotEqualTo(withNanos);
  }

  private ActionRecord save(ActionHash.Input in, byte[] hash, String payloadJson) {
    ActionRecord action =
        new ActionRecord(
                in.hashVersion(),
                in.roomRecordId(),
                in.roomSeq(),
                in.prevHash(),
                hash,
                in.contentDigest(),
                in.actorMemberId(),
                in.actorSide(),
                in.actorKey(),
                in.type(),
                in.idempotencyKey(),
                in.requestId(),
                in.seenVersionNo(),
                in.reason(),
                payloadJson,
                in.createdAt())
            .target(in.targetColumn(), in.targetId());
    entityManager.persist(action);
    return action;
  }

  /** 읽은 행동을 해시 입력으로 되돌린다. 실제 ActionWriter의 검증도 이 모양이 된다(이 테스트는 수리 건 대상만 쓴다). */
  private static ActionHash.Input readBack(ActionRecord a) {
    String column = a.getRepairCaseId() == null ? null : "repair_case_id";
    UUID target = a.getRepairCaseId();
    return new ActionHash.Input(
        a.getHashVersion(),
        a.getRoomRecordId(),
        a.getRoomSeq(),
        a.getPrevHash(),
        a.getContentDigest(),
        a.getActorMemberId(),
        a.getActorSide(),
        a.getActorKey(),
        a.getType(),
        a.getIdempotencyKey(),
        a.getSeenVersionNo(),
        a.getReason(),
        a.getPayload() == null ? null : JSON.readValue(a.getPayload(), Object.class),
        column,
        target,
        a.getCreatedAt(),
        a.getRequestId());
  }
}
