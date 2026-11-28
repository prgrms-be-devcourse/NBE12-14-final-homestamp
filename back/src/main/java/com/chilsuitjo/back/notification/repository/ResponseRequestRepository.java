package com.chilsuitjo.back.notification.repository;

import com.chilsuitjo.back.notification.entity.ResponseRequest;
import com.chilsuitjo.back.notification.rule.NoticeKind;
import com.chilsuitjo.back.notification.rule.RequestStatus;
import java.time.Instant;
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
   * 저장된 재통지 기한이 {@code now} 이전이고, 그 기한 이후에 만든 재통지가 없는 {@code status} 요청(재통지 작업자의 후보). 재통지는 기한이 지난
   * 뒤에만 만들어 지금 기회의 재통지는 늘 그 기한 이후다. 번호를 고쳐 기다림을 다시 시작하면 기한이 새 번호에 닿은 시각 + 48시간이 되어 옛 재통지는 그보다
   * 이르다(#370 T-2). 시각 하나의 앞뒤가 아니라 48시간 차이에 기대므로 서버 시계가 조금 어긋나도 빠뜨리지 않는다. notification 표만 보는 거친
   * 거르기이고, 정확한 판단은 잠근 뒤 확인({@code RenotifyStateStore.check}: 링크가 {@code REINVITED}가 아닌 재통지가 있으면
   * 건너뜀)이다. 재통지 뒤 기한을 다시 정하는 종류(고친 내용 확인 등)는 후보로 남을 수 있으나 잠근 뒤 건너뛴다. 잠그지 않는다: 작업자가 호실을 잠근 뒤 다시 본다.
   * 기한·ID 순서이고, {@code (afterDue, afterId)} 뒤부터 읽는다(한 번 도는 동안 건너뛴 요청이 앞을 막지 않게). 인덱스는 V1 {@code
   * ix_response_request_renotify}(기다림만).
   */
  @Query(
      """
      SELECT r FROM ResponseRequest r
      WHERE r.status = :status AND r.renotifyDueAt <= :now
        AND (r.renotifyDueAt > :afterDue OR (r.renotifyDueAt = :afterDue AND r.id > :afterId))
        AND NOT EXISTS (
          SELECT 1 FROM Notice n WHERE n.responseRequestId = r.id AND n.kind = :renotify
            AND n.createdAt >= r.renotifyDueAt)
      ORDER BY r.renotifyDueAt, r.id
      """)
  List<ResponseRequest> findRenotifyDue(
      @Param("status") RequestStatus status,
      @Param("now") Instant now,
      @Param("afterDue") Instant afterDue,
      @Param("afterId") UUID afterId,
      @Param("renotify") NoticeKind renotify,
      Limit limit);
}
