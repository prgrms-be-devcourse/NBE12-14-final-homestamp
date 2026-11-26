package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Notice;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

  /** 작업자가 결과를 적을 때. 통지는 작업자도 바꾸는 행이라 version 대신 행 잠금으로 막는다(04 "동시에 누름"). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT n FROM Notice n WHERE n.id = :id")
  Optional<Notice> findForUpdate(@Param("id") UUID id);

  /**
   * 전달 결과를 물을 시각이 된 보낸 문자를 잠가 가져온다(V1 {@code ix_notice_poll}). {@code SKIP LOCKED}라서 작업자 둘이 같은 통지를
   * 가져가지 않는다({@link OutboxRepository#lockDueSms}와 같은 방식). 트랜잭션 안에서만 부른다.
   *
   * <p>전달 확인 기한({@code delivery_check_due_at})이 없는 줄은 가져가지 않는다. 기한은 보낼 때 정해 저장하는 값이라 없으면 결과 없음을 언제
   * 실패로 볼지 알 수 없다. 지금 설정으로 기한을 지어내거나 실패로 끝내지 않고 그대로 둔다(V9 이전에 보냈거나 이전 버전 앱이 적은 줄, {@link
   * #countSentWithoutDeliveryCheckDue}로 센다).
   */
  @Query(
      value =
          """
          SELECT * FROM state.notice
          WHERE status = 'SENT' AND next_poll_at <= :now AND delivery_check_due_at IS NOT NULL
          ORDER BY next_poll_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Notice> lockDuePolls(@Param("now") Instant now, @Param("limit") int limit);

  /** 전달 확인 기한 없이 보낸(SENT) 상태인 통지 수. 전달 결과 작업자가 가져가지 않는 줄이라 운영이 알 수 있게 켜질 때 센다. */
  @Query(
      value =
          "SELECT count(*) FROM state.notice WHERE status = 'SENT' AND delivery_check_due_at IS NULL",
      nativeQuery = true)
  long countSentWithoutDeliveryCheckDue();
}
