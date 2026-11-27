package com.chilsuitjo.back.correction.service;

import com.chilsuitjo.back.correction.entity.CorrectionRequest;
import com.chilsuitjo.back.correction.repository.CorrectionRequestRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다른 도메인이 고친 내용을 읽는 창구. 읽기만 한다(고치기는 {@link CorrectionService}). */
@Service
@RequiredArgsConstructor
public class CorrectionQueryService {

  private final CorrectionRequestRepository corrections;

  /**
   * 이 호실에서 수리 건이 대상인 고친 내용 ID → 그 수리 건 ID. 고친 내용 응답·거두기 행동은 대상이 고친 내용이라, 수리 건 목록이 그 행동을 어느 건의 것으로
   * 셀지 찾을 때 쓴다(06 4-6 {@code lastActionAt}).
   */
  @Transactional(readOnly = true)
  public Map<UUID, UUID> repairCaseIdsByCorrection(UUID roomRecordId) {
    Map<UUID, UUID> result = new HashMap<>();
    for (CorrectionRequest row :
        corrections.findByRoomRecordIdAndRepairCaseIdNotNull(roomRecordId)) {
      result.put(row.getId(), row.getRepairCaseId());
    }
    return result;
  }
}
