package com.chilsuitjo.back.correction.dto;

import com.chilsuitjo.back.correction.rule.CorrectionTargetKind;
import java.util.List;
import java.util.UUID;

/**
 * 고치기 요청(06 4-6 {@code POST /api/corrections}). 결과(바로 바뀜·확인 요청)는 화면이 아니라 서버가 정한다(03 G).
 *
 * @param targetId {@code MOVE_IN_DATE}면 호실 기록 ID
 * @param changes 칸마다 새 값. 한 요청에 같은 칸은 한 번만
 * @param reason 고친 이유. 같이 확인한 뒤 고칠 때(상대 확인이 필요할 때)만 필수({@code REASON_REQUIRED})
 */
public record CorrectRequest(
    CorrectionTargetKind targetKind, UUID targetId, List<FieldChange> changes, String reason) {

  /**
   * 칸 하나의 새 값. 값의 모양은 기록마다 다르다(수리 건: {@code roomPartId}는 부위 ID 글자, {@code title}·{@code
   * description}은 글자, 설명을 비우려면 null).
   */
  public record FieldChange(String field, Object newValue) {}
}
