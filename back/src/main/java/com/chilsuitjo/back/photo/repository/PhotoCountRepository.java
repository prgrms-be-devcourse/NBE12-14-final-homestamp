package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.global.common.PartySide;
import com.chilsuitjo.back.photo.dto.PhotoTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 사진 붙이기가 세는 값: 대상 한도의 장수와 다음 표시 순서. 대상 칸이 여덟 개라 대상마다 조건을 만든다. */
@Repository
public class PhotoCountRepository {

  private final EntityManager entityManager;

  PhotoCountRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * 올리는 사람이 볼 수 있는 사진 수(decisions 2026-11-12·18). 지운 사진은 세지 않는다(06 0-1 {@code photo.max.repair}).
   * 공유를 그만둔 뒤 상대가 붙인 사진은 나에게 보이지 않으므로 세지 않는다(03 3-1 보이는 범위).
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
        """
            + (sharingStoppedAt == null
                ? ""
                : " AND (p.uploaded_by_side = :side OR p.created_at <= :stoppedAt)")
            + " AND ("
            + anyOf(targets, params)
            + ")";
    Query query = entityManager.createNativeQuery(sql).setParameter("room", roomRecordId);
    if (sharingStoppedAt != null) {
      query.setParameter("side", viewerSide.name()).setParameter("stoppedAt", sharingStoppedAt);
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

  private static String anyOf(Collection<PhotoTarget> targets, Map<String, Object> params) {
    List<String> conditions = new ArrayList<>();
    int i = 0;
    for (PhotoTarget target : targets) {
      conditions.add(condition(target, i++, params));
    }
    return String.join(" OR ", conditions);
  }

  private static String condition(PhotoTarget target, int i, Map<String, Object> params) {
    return switch (target) {
      case PhotoTarget.Ref ref -> {
        params.put("t" + i, ref.id());
        yield "p." + column(ref) + " = :t" + i;
      }
      case PhotoTarget.InspectionPart part -> {
        params.put("t" + i, part.inspectionId());
        params.put("r" + i, part.roomPartId());
        yield "(p.inspection_id = :t" + i + " AND p.room_part_id = :r" + i + ")";
      }
    };
  }

  /** {@code Photo.forTarget}과 같은 칸. 칸 이름은 이 switch에서만 나와 SQL에 사용자 값이 섞이지 않는다. */
  private static String column(PhotoTarget.Ref ref) {
    return switch (ref.type()) {
      case REPAIR_CASE -> "repair_case_id";
      case INSPECTION_ITEM -> "inspection_item_id";
      case PAYMENT -> "payment_id";
      case CAUSE_NOTE -> "cause_note_id";
      case SETTLEMENT_ITEM -> "settlement_item_id";
      case PROGRESS_NOTE -> "progress_note_id";
      case CORRECTION_REQUEST -> "correction_request_id";
    };
  }
}
