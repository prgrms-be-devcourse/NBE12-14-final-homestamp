package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.repair.rule.CauseBasis;
import java.util.List;
import java.util.UUID;

/**
 * 원인 설명 남기기·고치기 본문({@code POST /api/repair-cases/{id}/cause-notes}, 06 4-6·REP-8). 검사는 서비스가 한다: 근거
 * 종류가 없으면 {@code BASIS_REQUIRED}(REP-8a)라 Bean Validation으로 막지 않는다.
 *
 * @param text 설명(필수, 2,000자까지)
 * @param basis 근거 종류(필수): 내 추측 / 직접 봤어요 / 업체·전문가 소견 / 다른 사람에게 들었어요
 * @param expertName 근거가 업체·전문가 소견({@code EXPERT})일 때 업체 이름(선택, 50자까지). 다른 근거면 보내지 않는다
 * @param previousId 고칠 설명(내 지금 설명). 처음 남기면 비운다
 * @param uploadTickets 근거 사진(소견서·견적서·현장 사진)의 업로드 표 0~10개
 */
public record CauseNoteRequest(
    String text, CauseBasis basis, String expertName, UUID previousId, List<String> uploadTickets) {

  public CauseNoteRequest {
    text = text == null || text.isBlank() ? null : text;
    expertName = expertName == null || expertName.isBlank() ? null : expertName.strip();
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }
}
