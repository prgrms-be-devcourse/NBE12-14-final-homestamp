package com.chilsuitjo.back.action.service;

import com.chilsuitjo.back.action.dto.ActionCommand;
import com.chilsuitjo.back.action.dto.ActionEntry;
import com.chilsuitjo.back.action.dto.ActionTarget;
import com.chilsuitjo.back.action.entity.ActionChainHead;
import com.chilsuitjo.back.action.entity.ActionRecord;
import com.chilsuitjo.back.action.rule.ActionHash;
import com.chilsuitjo.back.action.rule.ActionType;
import com.chilsuitjo.back.action.rule.ContentDigest;
import com.chilsuitjo.back.global.common.TargetKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 행동 기록을 {@code record.action}에 쓰고 읽는다(05 2-3, 04 4-0 해시 체인). pipeline 안쪽 부품이라 package-private이다.
 * 다른 도메인이 pipeline을 거치지 않고 행동을 쓰지 못하게, 공개 Repository를 두지 않고 {@link EntityManager}를 직접 쓴다.
 *
 * <p>한 줄 쓰기:
 *
 * <ol>
 *   <li>호실의 체인 머리({@link ActionChainHead})를 {@code FOR UPDATE}로 읽어 {@code room_seq} = {@code
 *       last_seq} + 1, {@code prev_hash} = {@code last_hash}(첫 행동은 1과 {@link ActionHash#start()}).
 *       남은 마지막 행동이 아니라 머리에서 정하므로, 탈퇴로 끝 행동이 지워져도 번호를 다시 쓰지 않는다(05 2-10, #128). 행동이 있는데 머리가 없거나
 *       뒤처졌으면 쓰지 않고 실패한다
 *   <li>같은 트랜잭션에서 머리를 이 행동으로 옮긴다(첫 행동이면 만든다)
 *   <li>내용 지문({@link ContentDigest}), payload는 JSON으로 바꿨다가 다시 읽은 값으로 행동 해시({@link ActionHash})
 *   <li>저장하고 바로 {@code flush}: {@code UNIQUE(actor_key, idempotency_key)}·{@code
 *       UNIQUE(room_record_id, room_seq)} 충돌이 이 자리에서 {@code DataIntegrityViolationException}으로 난다
 * </ol>
 *
 * <p>같은 호실에 두 요청이 동시에 쓰지 않는 것은 pipeline 1단계 잠금({@code RoomGateReader.lockAndRead})이 맡는다. 잠금이 빠져도 같은
 * {@code room_seq}는 UNIQUE가 막아 체인이 갈라지지 않는다. 머리 행 잠금은 번호만 차례로 정할 뿐 도메인 상태 검사를 지켜 주지 않으므로 호실 잠금을
 * 대신하지 않는다. 머리가 아직 없는 호실에 첫 행동 두 개가 동시에 오면 {@code uq_action_chain_head_room}이 나중 요청을 막는다.
 *
 * <p>pipeline의 트랜잭션 안에서만 부른다({@link Propagation#MANDATORY}). {@code @Repository}라서 {@code flush}에서
 * 난 DB 오류가 Spring 예외({@code DataIntegrityViolationException})로 바뀐다. pipeline은 이 예외로 같은 키가 동시에 온 것을
 * 알아챈다. 부르는 쪽 실수로 던지는 {@code IllegalArgumentException}(자르지 않은 시각, 소수가 든 payload 등)도 {@code
 * InvalidDataAccessApiUsageException}으로 감싸져 나간다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JpaActionWriter implements ActionWriter, ActionReader {

  /**
   * payload를 JSON으로 바꾸는 데만 쓴다. 앱 설정(이름 바꾸기 등)이 해시 입력을 바꾸지 않도록 Spring의 mapper가 아니라 기본 설정을 따로 둔다. 이
   * 설정을 바꾸면 저장된 해시를 다시 계산할 수 없게 되니 바꾸지 않는다.
   */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @PersistenceContext private EntityManager entityManager;

  @Override
  public ActionEntry write(ActionCommand c, UUID requestId, Instant at) {
    Objects.requireNonNull(c.roomRecordId(), "roomRecordId");
    Objects.requireNonNull(requestId, "requestId");
    if (!at.equals(at.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException("시각은 pipeline이 마이크로초로 자른 값을 그대로 줘요: " + at);
    }
    String column = columnOf(c.target());
    UUID targetId = c.target() == null ? null : c.target().id();

    ActionChainHead head = lockHead(c.roomRecordId());
    checkHead(c.roomRecordId(), head);
    long roomSeq = head == null ? 1 : head.getLastSeq() + 1;
    byte[] prevHash = head == null ? ActionHash.start() : head.getLastHash();
    byte[] contentDigest = c.content() == null ? null : ContentDigest.compute(c.content());
    String payloadJson = c.payload().isEmpty() ? null : JSON.writeValueAsString(c.payload());

    byte[] hash =
        ActionHash.compute(
            new ActionHash.Input(
                ActionHash.VERSION,
                c.roomRecordId(),
                roomSeq,
                prevHash,
                contentDigest,
                c.actor().memberId(),
                c.actor().side(),
                c.actor().key(),
                c.type().name(),
                c.idempotencyKey(),
                c.seenVersionNo(),
                c.reason(),
                // 저장되는 jsonb·검증 때 읽는 값과 같은 입력이 되게, 도메인이 넘긴 Map이 아니라 JSON을 다시 읽은 값(05 2-3 payload)
                payloadJson == null ? null : JSON.readValue(payloadJson, Object.class),
                column,
                targetId,
                at,
                requestId));

    ActionRecord record =
        new ActionRecord(
            ActionHash.VERSION,
            c.roomRecordId(),
            roomSeq,
            prevHash,
            hash,
            contentDigest,
            c.actor().memberId(),
            c.actor().side(),
            c.actor().key(),
            c.type().name(),
            c.idempotencyKey(),
            requestId,
            c.seenVersionNo(),
            c.reason(),
            payloadJson,
            at);
    if (column != null) {
      record.target(column, targetId);
    }
    entityManager.persist(record);
    if (head == null) {
      entityManager.persist(new ActionChainHead(c.roomRecordId(), roomSeq, hash, at));
    } else {
      head.advance(roomSeq, hash);
    }
    entityManager.flush();
    return entryOf(record);
  }

  @Override
  public List<ActionEntry> findRequest(String actorKey, String idempotencyKey) {
    List<UUID> first =
        entityManager
            .createQuery(
                "select a.requestId from ActionRecord a"
                    + " where a.actorKey = :actorKey and a.idempotencyKey = :key",
                UUID.class)
            .setParameter("actorKey", actorKey)
            .setParameter("key", idempotencyKey)
            .getResultList();
    if (first.isEmpty()) {
      return List.of();
    }
    // 뒤 행동은 주체가 다를 수 있다(딸린 행동은 server). 키가 아니라 request_id로 모은다(#66).
    return entityManager
        .createQuery(
            "select a from ActionRecord a where a.requestId = :requestId order by a.roomSeq",
            ActionRecord.class)
        .setParameter("requestId", first.getFirst())
        .getResultList()
        .stream()
        .map(JpaActionWriter::entryOf)
        .toList();
  }

  /** {@link TargetKind#ROOM_RECORD}면 그 호실에서 대상 칸 없이 남은 행동(호실 기록 자체가 대상)을 돌려준다. */
  @Override
  public List<ActionEntry> findByTarget(ActionTarget target) {
    String column = columnOf(target);
    // 칸 이름은 ActionHash.TARGET_COLUMNS에서만 오므로 SQL에 이어 붙여도 된다.
    String where =
        column == null
            ? "room_record_id = :id AND "
                + String.join(" IS NULL AND ", ActionHash.TARGET_COLUMNS)
                + " IS NULL"
            : column + " = :id";
    @SuppressWarnings("unchecked")
    List<ActionRecord> records =
        entityManager
            .createNativeQuery(
                "SELECT * FROM record.action WHERE " + where + " ORDER BY room_seq",
                ActionRecord.class)
            .setParameter("id", target.id())
            .getResultList();
    return records.stream().map(JpaActionWriter::entryOf).toList();
  }

  @Override
  public List<ActionEntry> findByTargets(UUID roomRecordId, Collection<ActionTarget> targets) {
    Map<String, List<UUID>> idsByColumn = new LinkedHashMap<>();
    for (ActionTarget target : targets) {
      String column = columnOf(target);
      if (column == null) {
        throw new IllegalArgumentException("호실 자체 대상은 findByTarget으로 읽어요");
      }
      idsByColumn.computeIfAbsent(column, c -> new ArrayList<>()).add(target.id());
    }
    if (idsByColumn.isEmpty()) {
      return List.of();
    }
    // 칸 이름은 ActionHash.TARGET_COLUMNS에서만 오므로 SQL에 이어 붙여도 된다(findByTarget과 같음)
    List<String> conditions = new ArrayList<>();
    List<String> columns = new ArrayList<>(idsByColumn.keySet());
    for (int i = 0; i < columns.size(); i++) {
      conditions.add(columns.get(i) + " IN (:ids" + i + ")");
    }
    var query =
        entityManager
            .createNativeQuery(
                "SELECT * FROM record.action WHERE room_record_id = :room AND ("
                    + String.join(" OR ", conditions)
                    + ") ORDER BY room_seq",
                ActionRecord.class)
            .setParameter("room", roomRecordId);
    for (int i = 0; i < columns.size(); i++) {
      query.setParameter("ids" + i, idsByColumn.get(columns.get(i)));
    }
    @SuppressWarnings("unchecked")
    List<ActionRecord> records = query.getResultList();
    return records.stream().map(JpaActionWriter::entryOf).toList();
  }

  @Override
  public List<ActionEntry> findByRoom(UUID roomRecordId) {
    return entityManager
        .createQuery(
            "select a from ActionRecord a where a.roomRecordId = :room order by a.roomSeq",
            ActionRecord.class)
        .setParameter("room", roomRecordId)
        .getResultList()
        .stream()
        .map(JpaActionWriter::entryOf)
        .toList();
  }

  /**
   * 호실의 체인 머리를 {@code FOR UPDATE}로 읽는다. 첫 행동 전이면 null. 호실 잠금(pipeline 1단계) 안에서만 잠그고, 작업자는 이 행을 잠그지
   * 않는다.
   */
  private ActionChainHead lockHead(UUID roomRecordId) {
    List<ActionChainHead> head =
        entityManager
            .createQuery(
                "select h from ActionChainHead h where h.roomRecordId = :room",
                ActionChainHead.class)
            .setParameter("room", roomRecordId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    return head.isEmpty() ? null : head.getFirst();
  }

  /**
   * 머리는 남은 마지막 행동보다 작을 수 없다(지운 번호만큼 클 수는 있다, 05 2-10). 행동이 있는데 머리가 없거나 머리가 더 작으면 머리를 쓰는 길을 건너뛴 버그라,
   * 번호를 다시 쓰기 전에 멈춘다.
   */
  private void checkHead(UUID roomRecordId, ActionChainHead head) {
    Long lastSeq =
        entityManager
            .createQuery(
                "select max(a.roomSeq) from ActionRecord a where a.roomRecordId = :room",
                Long.class)
            .setParameter("room", roomRecordId)
            .getSingleResult();
    long kept = lastSeq == null ? 0 : lastSeq;
    long headSeq = head == null ? 0 : head.getLastSeq();
    if (kept > headSeq) {
      throw new IllegalStateException(
          "체인 머리가 남은 행동보다 뒤처졌어요: 호실 " + roomRecordId + ", 머리 " + headSeq + ", 행동 " + kept);
    }
  }

  /** 대상 칸 이름. 종류 이름을 소문자로 바꾸고 {@code _id}를 붙인 것이 05 {@code action}의 칸 이름이다. 호실 기록 자체면 null. */
  static String columnOf(ActionTarget target) {
    if (target == null || target.kind() == TargetKind.ROOM_RECORD) {
      return null;
    }
    String column = target.kind().name().toLowerCase(Locale.ROOT) + "_id";
    if (!ActionHash.TARGET_COLUMNS.contains(column)) {
      throw new IllegalArgumentException("action에 없는 대상 칸이에요: " + column);
    }
    return column;
  }

  private static ActionTarget targetOf(ActionRecord record) {
    String column = record.targetColumn();
    if (column == null) {
      return null;
    }
    String kind = column.substring(0, column.length() - "_id".length()).toUpperCase(Locale.ROOT);
    return ActionTarget.of(TargetKind.valueOf(kind), record.targetId());
  }

  @SuppressWarnings("unchecked")
  private static ActionEntry entryOf(ActionRecord record) {
    Map<String, Object> payload =
        record.getPayload() == null ? Map.of() : JSON.readValue(record.getPayload(), Map.class);
    return new ActionEntry(
        record.getId(),
        record.getRoomRecordId(),
        record.getRoomSeq(),
        ActionType.valueOf(record.getType()),
        record.getActorSide(),
        record.getActorKey(),
        record.getIdempotencyKey(),
        record.getRequestId(),
        targetOf(record),
        record.getSeenVersionNo(),
        record.getContentDigest(),
        record.getReason(),
        payload,
        record.getCreatedAt());
  }
}
