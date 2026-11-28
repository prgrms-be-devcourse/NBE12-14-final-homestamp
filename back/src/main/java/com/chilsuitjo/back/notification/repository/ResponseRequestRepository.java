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
   * 저장된 재통지 기한이 {@code now} 이전이고 지금 응답 기회에 재통지가 없어 보이는 {@code status} 요청(재통지 작업자의 후보). notification
   * 표만 보는 거친 거르기이고, 정확한 판단은 잠근 뒤 확인({@code RenotifyStateStore.check}: 링크가 {@code REINVITED}가 아닌
   * 재통지가 있으면 건너뜀)이다.
   *
   * <p>재통지가 옛 기회의 것으로 보이는 것은 <b>둘 다</b>일 때다(#370 T-2): 지금 재통지 기한보다 먼저 만들었고, 그와 같은 시각 또는 뒤에 같은 요청의
   * {@code evidence}(요청·초대) 통지가 만들어졌다. 번호를 고쳐 기다림을 다시 시작하면 둘 다 맞는다(새 통지를 만들고, 기한은 새 번호에 닿은 시각 +
   * 48시간). 같은 기회에서 기한만 다시 정한 요청({@code recount}·{@code rewait}: 고친 내용 확인 등)은 새 요청·초대 통지가 없어 지금 기회의
   * 재통지로 남아 후보가 아니다(잠근 뒤 건너뛰기를 되풀이하지 않음). 통지끼리의 시각 비교는 같은 서버 시계에 기댄다: 지금 운영은 인스턴스 하나다(04, infra
   * {@code aws_instance.web}). 여러 대로 늘리면 다시 본다(decisions 2026-11-29 T-2). 같은 기회에 요청·초대 통지를 더 만드는
   * 기능(NOTI-1 IN_APP 대체 등)이 생기면 이 통지 조건을 그때 다시 본다(아니면 기한을 다시 정한 요청이 잠근 뒤 건너뛰기를 되풀이함).
   *
   * <p>잠그지 않는다: 작업자가 호실을 잠근 뒤 다시 본다. 기한·ID 순서이고, {@code (afterDue, afterId)} 뒤부터 읽는다(한 번 도는 동안 건너뛴
   * 요청이 앞을 막지 않게). 인덱스는 V1 {@code ix_response_request_renotify}(기다림만).
   */
  @Query(
      """
      SELECT r FROM ResponseRequest r
      WHERE r.status = :status AND r.renotifyDueAt <= :now
        AND (r.renotifyDueAt > :afterDue OR (r.renotifyDueAt = :afterDue AND r.id > :afterId))
        AND NOT EXISTS (
          SELECT 1 FROM Notice n WHERE n.responseRequestId = r.id AND n.kind = :renotify
            AND (n.createdAt >= r.renotifyDueAt
              OR NOT EXISTS (
                SELECT 1 FROM Notice m
                WHERE m.responseRequestId = r.id AND m.kind IN :evidence
                  AND m.createdAt >= n.createdAt)))
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
