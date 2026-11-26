package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.Notice;
import com.chilsuitjo.back.notification.rule.NoticeChannel;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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
   * 이 호실에서 넘겨받은 링크를 가리키는 문자 통지를 모두 잠근다(#100 U2 취소). 상태로 거르지 않는다: 문자 줄이 {@code PENDING}인데 통지가 {@code
   * REQUESTED}가 아닌 이상한 조합도 찾아야 해서다. 작업자가 결과를 적을 때와 같이 통지를 문자 줄보다 먼저 잠그고, 여러 행은 ID 순서로 잠근다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT n FROM Notice n
      WHERE n.roomRecordId = :roomRecordId AND n.linkId IN :linkIds AND n.channel = :channel
      ORDER BY n.id
      """)
  List<Notice> lockByLinks(
      @Param("roomRecordId") UUID roomRecordId,
      @Param("linkIds") Collection<UUID> linkIds,
      @Param("channel") NoticeChannel channel);
}
