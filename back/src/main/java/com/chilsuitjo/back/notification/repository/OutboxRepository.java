package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Outbox;
import com.chilsuitjo.back.notification.rule.OutboxKind;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

  /**
   * 보낼 시각이 지난 문자 줄을 잠가 가져온다. {@code SKIP LOCKED}라서 작업자 둘(블루/그린이 겹칠 때)이 같은 줄을 가져가지 않는다(04 4-2). 트랜잭션
   * 안에서만 부른다.
   */
  @Query(
      value =
          """
          SELECT * FROM state.outbox
          WHERE status = 'PENDING' AND kind = 'SMS' AND next_attempt_at <= :now
          ORDER BY next_attempt_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Outbox> lockDueSms(@Param("now") Instant now, @Param("limit") int limit);

  /**
   * 중복 방지 키로 문자 줄을 잠근다(#100 U2 취소). {@code SKIP LOCKED}가 아니라 기다린다: 작업자가 가져가는 중이면 그 결과({@code
   * SENDING}이면 취소 안 함, 되돌아가면 {@code PENDING}이라 취소)를 보고 정해야 해서다. 상태로 거르지 않고, 여러 행은 ID 순서로 잠근다. 종류도
   * 함께 본다: 키 모양이 같아도 다른 종류의 작업(파일 삭제·감사 기록 등)은 문자 줄이 아니다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM Outbox o WHERE o.kind = :kind AND o.dedupeKey IN :dedupeKeys ORDER BY o.id")
  List<Outbox> lockByDedupeKeys(
      @Param("kind") OutboxKind kind, @Param("dedupeKeys") Collection<String> dedupeKeys);

  /**
   * 고아 파일 확인 줄을 남기거나, 같은 키의 줄이 남아 있으면 시각을 새로 정한다(05 불변식 17). {@code DO NOTHING}이 아니라 {@code DO
   * UPDATE}다: 그 줄을 확인 작업이 {@code FOR UPDATE}로 잡고 있으면 끝날 때까지 기다려야 하고(확인 작업이 곧 지울 파일을 다시 복사하지 않게),
   * 시각도 늦춰야 지난 시각의 줄이 방금 복사한 파일을 지우지 않는다.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO state.outbox AS o
            (id, kind, dedupe_key, payload, status, attempts, next_attempt_at, created_at, version)
          VALUES (:id, 'ORPHAN_CHECK', :dedupeKey, CAST(:payload AS jsonb), 'PENDING', 0,
            :nextAttemptAt, :createdAt, 0)
          ON CONFLICT (dedupe_key) DO UPDATE
            SET payload = EXCLUDED.payload, status = 'PENDING', attempts = 0,
                next_attempt_at = EXCLUDED.next_attempt_at, version = o.version + 1
          """,
      nativeQuery = true)
  void upsertOrphanCheck(
      @Param("id") UUID id,
      @Param("dedupeKey") String dedupeKey,
      @Param("payload") String payload,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("createdAt") Instant createdAt);

  /** 고아 파일 확인 줄을 지운다. 사진 카드를 저장하는 트랜잭션에서 부른다. */
  @Modifying
  @Query("DELETE FROM Outbox o WHERE o.kind = :kind AND o.dedupeKey IN :dedupeKeys")
  int deleteByKindAndDedupeKeys(
      @Param("kind") OutboxKind kind, @Param("dedupeKeys") Collection<String> dedupeKeys);
}
