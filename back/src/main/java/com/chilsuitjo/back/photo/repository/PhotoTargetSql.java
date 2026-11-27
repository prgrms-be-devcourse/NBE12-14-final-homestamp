package com.chilsuitjo.back.photo.repository;

import com.chilsuitjo.back.photo.dto.PhotoTarget;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code record.photo p}에서 대상을 고르는 SQL 조건. 대상 칸이 여덟 개라 대상마다 조건을 만든다. 사진을 세는 쪽({@link
 * PhotoCountRepository})과 읽는 쪽({@link PhotoTargetRepository})이 같이 쓴다.
 *
 * <p>칸 이름은 {@link #column}의 switch에서만 나오고 값은 모두 {@code params}로 넘겨서, SQL에 사용자 값이 섞이지 않는다.
 */
final class PhotoTargetSql {

  private PhotoTargetSql() {}

  /** 대상 중 하나에 붙은 사진. {@code targets}는 비어 있지 않아야 한다(비면 {@code ()}가 되어 SQL이 틀린다). */
  static String anyOf(Collection<PhotoTarget> targets, Map<String, Object> params) {
    List<String> conditions = new ArrayList<>();
    int i = 0;
    for (PhotoTarget target : targets) {
      conditions.add(condition(target, i++, params));
    }
    return String.join(" OR ", conditions);
  }

  /** 대상 하나에 붙은 사진. {@code i}는 파라미터 이름이 겹치지 않게 붙이는 번호다. */
  static String condition(PhotoTarget target, int i, Map<String, Object> params) {
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

  /** {@code Photo.forTarget}과 같은 칸. */
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
