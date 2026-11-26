package com.chilsuitjo.back.action.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chilsuitjo.back.TestcontainersConfiguration;
import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.dto.Actor;
import com.chilsuitjo.back.action.dto.DigestField;
import com.chilsuitjo.back.action.dto.DigestInput;
import com.chilsuitjo.back.action.dto.PhotoDigest;
import com.chilsuitjo.back.action.dto.SavedState;
import com.chilsuitjo.back.action.entity.ActionRecord;
import com.chilsuitjo.back.action.rule.ActionHash;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.Answer;
import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.global.common.TargetKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 진짜 행동 기록 저장(05 2-3, 04 4-0 해시 체인). 실제 PostgreSQL에 쓰고 읽는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties =
        "spring.datasource.hikari.connection-init-sql=SET search_path TO state, record, log, public")
@Transactional
class JpaActionWriterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private ActionWriter writer;
  @Autowired private ActionReader reader;
  @Autowired private JdbcTemplate jdbc;
  @PersistenceContext private EntityManager entityManager;

  private final Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private UUID room;
  private UUID repair;
  private final Actor tenant = Actor.member(UUID.randomUUID()).withSide(PartySide.TENANT);

  @BeforeEach
  void setUp() {
    jdbc.update(
        "INSERT INTO region (code, sido, dong) VALUES ('9999999999', '테스트', '테스트동')"
            + " ON CONFLICT DO NOTHING");
    room = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '테스트 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        room);
    repair = repairCase(room);
    jdbc.update(
        "INSERT INTO member (id, status, created_at, version) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, 0)",
        tenant.memberId());
  }

  @Test
  void 체인을_잇고_DB에서_읽은_값으로_다시_계산해도_같은_해시() {
    UUID requestId = UUID.randomUUID();
    DigestInput content = content();
    ActionEntry first =
        writer.write(
            command(tenant, ActionType.REGISTER, "k1", ActionTarget.repairCase(repair))
                .withContent(content),
            requestId,
            at);
    ActionEntry second =
        writer.write(
            command(tenant, ActionType.RESPOND_DEFECT, "k2", ActionTarget.repairCase(repair)),
            UUID.randomUUID(),
            at.plusSeconds(1));

    assertThat(first.roomSeq()).isEqualTo(1);
    assertThat(second.roomSeq()).isEqualTo(2);

    entityManager.clear();
    List<ActionRecord> stored =
        entityManager
            .createQuery(
                "select a from ActionRecord a where a.roomRecordId = :room order by a.roomSeq",
                ActionRecord.class)
            .setParameter("room", room)
            .getResultList();
    assertThat(stored.get(0).getPrevHash()).isEqualTo(ActionHash.start());
    assertThat(stored.get(1).getPrevHash()).isEqualTo(stored.get(0).getHash());
    assertThat(stored.get(0).getContentDigest()).isEqualTo(ContentDigest.compute(content));
    assertThat(stored.get(0).getHashVersion()).isEqualTo(ActionHash.VERSION);
    assertThat(stored.get(0).getCreatedAt()).isEqualTo(at);
    for (ActionRecord a : stored) {
      assertThat(ActionHash.compute(readBack(a))).isEqualTo(a.getHash());
    }
  }

  @Test
  void payload는_JSON을_거친_값으로_돌려주고_도메인_record로_읽힌다() {
    UUID someone = UUID.randomUUID();
    ActionCommand c =
        new ActionCommand(
            room,
            tenant,
            ActionType.RESPOND_DEFECT,
            "payload",
            ActionTarget.repairCase(repair),
            1,
            null,
            null,
            Map.of("answer", Answer.NEED_MORE, "who", someone));

    ActionEntry written = writer.write(c, UUID.randomUUID(), at);

    assertThat(written.payload()).containsEntry("answer", "NEED_MORE");
    assertThat(written.payloadAs(Sample.class)).isEqualTo(new Sample(Answer.NEED_MORE, someone));
    assertThat(reader.findByRoom(room).getFirst().payload()).isEqualTo(written.payload());
  }

  @Test
  void 사진_목록_payload도_DB에서_읽은_값으로_다시_계산하면_같은_해시() {
    // #141: 사진 지문은 판 지문이 아니라 payload로 해시에 들어간다. jsonb를 거친 목록·숫자가 같은 해시 입력이어야 한다
    SavedState saved =
        SavedState.unchanged()
            .withPhotos(
                List.of(
                    new PhotoDigest(UUID.randomUUID(), "b".repeat(64), 1),
                    new PhotoDigest(UUID.randomUUID(), "a".repeat(64), 0)));
    ActionEntry written =
        writer.write(
            command(tenant, ActionType.REGISTER, "photos", ActionTarget.repairCase(repair))
                .withExtraPayload(saved.payload()),
            UUID.randomUUID(),
            at);

    assertThat(written.payload().get(SavedState.PHOTOS)).isEqualTo(saved.payload().get("photos"));
    entityManager.clear();
    ActionRecord stored = entityManager.find(ActionRecord.class, written.id());
    assertThat(ActionHash.compute(readBack(stored))).isEqualTo(stored.getHash());
  }

  record Sample(Answer answer, UUID who) {}

  @Test
  void 같은_주체_같은_키는_쓰는_자리에서_막힌다() {
    writer.write(command(tenant, ActionType.REGISTER, "same", null), UUID.randomUUID(), at);

    assertThatThrownBy(() ->
            writer.write(command(tenant, ActionType.REGISTER, "same", null), UUID.randomUUID(), at))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 한_요청의_행동은_주체가_달라도_request_id로_모인다() {
    UUID requestId = UUID.randomUUID();
    writer.write(
        command(tenant, ActionType.REGISTER, "req", ActionTarget.repairCase(repair)),
        requestId,
        at);
    writer.write(
        command(Actor.server(), ActionType.RENOTIFY, requestId + "#2", null), requestId, at);
    writer.write(command(tenant, ActionType.MOVE_OUT, "other", null), UUID.randomUUID(), at);

    List<ActionEntry> done = writer.findRequest(tenant.key(), "req");

    assertThat(done)
        .extracting(ActionEntry::type)
        .containsExactly(ActionType.REGISTER, ActionType.RENOTIFY);
    assertThat(writer.findRequest(tenant.key(), "없는 키")).isEmpty();
    assertThat(writer.findRequest("server", "req")).isEmpty();
  }

  @Test
  void 대상별_호실별로_읽는다() {
    writer.write(
        command(tenant, ActionType.REGISTER, "t1", ActionTarget.repairCase(repair)),
        UUID.randomUUID(),
        at);
    writer.write(command(tenant, ActionType.MOVE_OUT, "t2", null), UUID.randomUUID(), at);

    assertThat(reader.findByTarget(ActionTarget.repairCase(repair)))
        .singleElement()
        .satisfies(e -> assertThat(e.target()).isEqualTo(ActionTarget.repairCase(repair)));
    assertThat(reader.findByTarget(ActionTarget.roomRecord(room)))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(ActionType.MOVE_OUT);
          assertThat(e.target()).isNull();
        });
    assertThat(reader.findByRoom(room)).extracting(ActionEntry::roomSeq).containsExactly(1L, 2L);
  }

  @Test
  void 호실이_다르면_room_seq도_따로_센다() {
    UUID otherRoom = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_record
          (id, region_code, alias, status, created_by_side, created_at, version)
        VALUES (?, '9999999999', '다른 호실', 'LIVING', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        otherRoom);
    writer.write(command(tenant, ActionType.MOVE_OUT, "r1", null), UUID.randomUUID(), at);

    ActionEntry other =
        writer.write(
            command(tenant, ActionType.MOVE_OUT, "r2", null).withRoomRecordId(otherRoom),
            UUID.randomUUID(),
            at);

    assertThat(other.roomSeq()).isEqualTo(1);
  }

  @Test
  void 링크_주체의_열람은_링크를_대상으로_저장하고_다시_읽는다() {
    // #89 LINK_VIEW: 회원 칸 없이 측과 link:<UUID> 주체로 남고, 대상은 그 링크다(#94 record(종류, 대상, payload)).
    UUID link = link(room);
    Actor viewer = Actor.link(link, PartySide.LANDLORD);
    ActionTarget target = ActionTarget.of(TargetKind.LINK, link);

    ActionEntry written =
        writer.write(
            command(viewer, ActionType.LINK_VIEW, "link-view:" + link + ":1", target),
            UUID.randomUUID(),
            at);

    entityManager.clear();
    ActionRecord stored = entityManager.find(ActionRecord.class, written.id());
    assertThat(stored.getActorMemberId()).isNull();
    assertThat(stored.getActorSide()).isEqualTo(PartySide.LANDLORD);
    assertThat(stored.getActorKey()).isEqualTo("link:" + link);
    assertThat(stored.targetColumn()).isEqualTo("link_id");
    assertThat(ActionHash.compute(readBack(stored))).isEqualTo(stored.getHash());
    assertThat(reader.findByTarget(target))
        .singleElement()
        .satisfies(e -> assertThat(e.type()).isEqualTo(ActionType.LINK_VIEW));
  }

  @Test
  void 같은_호실에_같은_번호가_두_번_들어가면_DB가_막는다() {
    // 진짜 잠금(ROOM-2) 전 마지막 방어선: 두 요청이 같은 마지막 행동을 읽고 같은 room_seq를 쓰는 경우(uq_action_room_seq).
    writer.write(command(tenant, ActionType.MOVE_OUT, "s1", null), UUID.randomUUID(), at);
    ActionRecord last =
        entityManager.find(ActionRecord.class, reader.findByRoom(room).getFirst().id());

    assertThatThrownBy(
            () -> {
              entityManager.persist(
                  new ActionRecord(
                      ActionHash.VERSION,
                      room,
                      last.getRoomSeq(),
                      ActionHash.start(),
                      new byte[32],
                      null,
                      tenant.memberId(),
                      PartySide.TENANT,
                      tenant.key(),
                      "MOVE_OUT",
                      "s2",
                      UUID.randomUUID(),
                      null,
                      null,
                      null,
                      at));
              entityManager.flush();
            })
        .hasMessageContaining("uq_action_room_seq");
  }

  @Test
  void 마이크로초로_자르지_않은_시각은_거절한다() {
    assertThatThrownBy(() ->
            writer.write(
                command(tenant, ActionType.MOVE_OUT, "nanos", null),
                UUID.randomUUID(),
                Instant.parse("2026-11-19T05:00:00.123456789Z")))
        // @Repository가 IllegalArgumentException을 Spring 예외로 감싼다.
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("마이크로초");
  }

  private ActionCommand command(Actor actor, ActionType type, String key, ActionTarget target) {
    return new ActionCommand(room, actor, type, key, target, null, null, null, null);
  }

  private DigestInput content() {
    return new DigestInput(
        "repair_content", UUID.randomUUID(), 1, List.of(new DigestField("title", "누수")));
  }

  /** 읽은 행동을 해시 입력으로 되돌린다. 체인 검증도 이 모양이다. */
  private static ActionHash.Input readBack(ActionRecord a) {
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
        a.targetColumn(),
        a.targetId(),
        a.getCreatedAt(),
        a.getRequestId());
  }

  private UUID link(UUID roomId) {
    UUID party = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO party (id, room_record_id, side, status, created_at, version)
        VALUES (?, ?, 'LANDLORD', 'INVITED', CURRENT_TIMESTAMP, 0)
        """,
        party,
        roomId);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO link (id, room_record_id, kind, party_id, token_hash, issued_by_member_id,
          created_at, version)
        VALUES (?, ?, 'INVITE', ?, ?, ?, CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        party,
        "hash-" + id,
        tenant.memberId());
    return id;
  }

  private UUID repairCase(UUID roomId) {
    UUID part = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO room_part (id, room_record_id, zone, name, sort_order, created_at, version)
        VALUES (?, ?, '안방', '천장', 0, CURRENT_TIMESTAMP, 0)
        """,
        part,
        roomId);
    UUID id = UUID.randomUUID();
    UUID contentId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO repair_case (id, room_record_id, current_content_id, status, defect_ack,
          money_status, created_by_side, created_at, version)
        VALUES (?, ?, ?, 'NOT_STARTED', 'PENDING', 'UNDECIDED', 'TENANT', CURRENT_TIMESTAMP, 0)
        """,
        id,
        roomId,
        contentId);
    jdbc.update(
        """
        INSERT INTO repair_content (id, room_record_id, repair_case_id, no, room_part_id, title,
          created_by_side, created_at)
        VALUES (?, ?, ?, 1, ?, '누수', 'TENANT', CURRENT_TIMESTAMP)
        """,
        contentId,
        roomId,
        id,
        part);
    return id;
  }
}
