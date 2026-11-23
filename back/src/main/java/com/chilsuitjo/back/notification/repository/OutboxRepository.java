package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Outbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
