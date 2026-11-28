package com.chilsuitjo.back.photo.repository;

import static com.chilsuitjo.back.photo.repository.PhotoTargetSql.anyOf;
import static com.chilsuitjo.back.photo.repository.PhotoTargetSql.condition;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 사진 붙이기가 세는 값: 대상 한도의 장수와 다음 표시 순서. 대상 조건은 {@link PhotoTargetSql}이 만든다. */
@Repository
public class PhotoCountRepository {

  private final EntityManager entityManager;

  PhotoCountRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * 올리는 사람이 볼 수 있는 사진 수(decisions 2026-11-12·18). 지운 사진은 세지 않는다(06 0-1 {@code photo.max.repair}).
   * 상대의 사진은 {@code PhotoSight.visibleTo}와 같은 조건으로 센다: 아직 상대에게 보이지 않은 사진(작성 중 점검, #295)과 공유를 그만둔 뒤
   * 보이기 시작한 사진은 세지 않는다(03 3-1 보이는 범위).
   *
   * @param sharingStoppedAt 공유를 그만둔 시각. 없으면 null
   */
  public int countVisible(
      UUID roomRecordId,
      PartySide viewerSide,
      Instant sharingStoppedAt,
      Collection<PhotoTarget> targets) {
    if (targets.isEmpty()) {
      return 0;
    }
    Map<String, Object> params = new LinkedHashMap<>();
    String sql =
        """
        SELECT count(*) FROM record.photo p
        JOIN state.photo_visibility v ON v.photo_id = p.id
        WHERE p.room_record_id = :room AND v.erased_at IS NULL
          AND (p.uploaded_by_side = :side OR (v.released_at IS NOT NULL
        """
            + (sharingStoppedAt == null ? "" : " AND v.released_at <= :stoppedAt")
            + ")) AND ("
            + anyOf(targets, params)
            + ")";
    Query query =
        entityManager
            .createNativeQuery(sql)
            .setParameter("room", roomRecordId)
            .setParameter("side", viewerSide.name());
    if (sharingStoppedAt != null) {
      query.setParameter("stoppedAt", sharingStoppedAt);
    }
    params.forEach(query::setParameter);
    return ((Number) query.getSingleResult()).intValue();
  }

  /** 같은 대상 안의 다음 표시 순서. 사진이 없으면 0. 지운 사진도 자리를 차지한다(순서는 만들 때 정하고 바꾸지 않음, 05 2-8). */
  public int nextSortOrder(UUID roomRecordId, PhotoTarget target) {
    Map<String, Object> params = new LinkedHashMap<>();
    Query query =
        entityManager
            .createNativeQuery(
                "SELECT COALESCE(max(p.sort_order), -1) + 1 FROM record.photo p"
                    + " WHERE p.room_record_id = :room AND "
                    + condition(target, 0, params))
            .setParameter("room", roomRecordId);
    params.forEach(query::setParameter);
    return ((Number) query.getSingleResult()).intValue();
  }
}
