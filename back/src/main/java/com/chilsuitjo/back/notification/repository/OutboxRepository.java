package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Outbox;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
   * SENDING}이면 취소 안 함, 되돌아가면 {@code PENDING}이라 취소)를 보고 정해야 해서다. 상태로 거르지 않고, 여러 행은 ID 순서로 잠근다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM Outbox o WHERE o.dedupeKey IN :dedupeKeys ORDER BY o.id")
  List<Outbox> lockByDedupeKeys(@Param("dedupeKeys") Collection<String> dedupeKeys);
}
