package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 응답 요청 저장소. 대상 + 종류 조회는 {@link ResponseRequestLookup}(V1 유니크별로 나눈 계약)에 있다. */
public interface ResponseRequestRepository
    extends JpaRepository<ResponseRequest, UUID>, ResponseRequestLookup {

  /**
   * 저장된 재통지 기한이 {@code now} 이전이고 지금 응답 기회에 재통지({@code kind = renotify} 통지)가 없는 {@code status}
   * 요청(재통지 작업자의 후보). 재통지 뒤에 {@code evidence} 종류 통지가 새로 생겼으면 그 재통지는 옛 기회의 것이라 세지 않는다(#370 T-2,
   * {@link NoticeRepository#existsCurrentRenotify}와 같은 기준). 잠그지 않는다: 작업자가 호실을 잠근 뒤 다시 본다. 기한·ID
   * 순서이고, {@code (afterDue, afterId)} 뒤부터 읽는다(한 번 도는 동안 건너뛴 요청이 앞을 막지 않게). 인덱스는 V1 {@code
   * ix_response_request_renotify}(기다림만).
   */
  @Query(
      """
      SELECT r FROM ResponseRequest r
      WHERE r.status = :status AND r.renotifyDueAt <= :now
        AND (r.renotifyDueAt > :afterDue OR (r.renotifyDueAt = :afterDue AND r.id > :afterId))
        AND NOT EXISTS (
          SELECT 1 FROM Notice n WHERE n.responseRequestId = r.id AND n.kind = :renotify
            AND NOT EXISTS (
              SELECT 1 FROM Notice m
              WHERE m.responseRequestId = r.id AND m.kind IN :evidence
                AND m.createdAt > n.createdAt))
      ORDER BY r.renotifyDueAt, r.id
      """)
  List<ResponseRequest> findRenotifyDue(
      @Param("status") RequestStatus status,
      @Param("now") Instant now,
      @Param("afterDue") Instant afterDue,
      @Param("afterId") UUID afterId,
      @Param("renotify") NoticeKind renotify,
      @Param("evidence") Collection<NoticeKind> evidence,
      Limit limit);
}
