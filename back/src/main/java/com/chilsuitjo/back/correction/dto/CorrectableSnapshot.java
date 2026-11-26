package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.global.common.PartySide;
import java.util.Map;
import java.util.UUID;

/**
 * 고칠 기록의 지금 모습.
 *
 * @param recordedBySide 원래 기록한 측. 이 측만 고칠 수 있다
 * @param confirmedVersionId 도장 찍힌 판. null이면 도장 전
 * @param confirmedNo 도장 찍힌 판의 번호(화면 앞모습, 응답의 본 번호와 비교). 도장 전이면 null
 * @param fields 고칠 수 있는 칸 이름 → 앞모습의 값({@code SAME_VALUE} 검사용). 도장 뒤면 도장 판, 도장 전이면 지금 판의 값이고 모양은
 *     {@code Correctable.normalize}가 돌려주는 값과 같다. 값이 없는 칸은 null
 * @param closedByBoth 처리 완료(양측) 수리 건이면 true → {@code CLOSED_BY_BOTH}
 */
public record CorrectableSnapshot(
    UUID roomRecordId,
    PartySide recordedBySide,
    UUID currentVersionId,
    UUID confirmedVersionId,
    int currentNo,
    Integer confirmedNo,
    Map<String, Object> fields,
    boolean closedByBoth) {

  public boolean stamped() {
    return confirmedVersionId != null;
  }
}
