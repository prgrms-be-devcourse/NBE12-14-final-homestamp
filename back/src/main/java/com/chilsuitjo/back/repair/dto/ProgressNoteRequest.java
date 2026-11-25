package com.chilsuitjo.back.repair.dto;

import java.util.List;

/**
 * 진행 상황 남기기(06 4-6 {@code POST /api/repair-cases/{id}/progress-notes}, REP-6). 글·사진 중 하나 이상이 없으면
 * {@code EVIDENCE_REQUIRED}다.
 *
 * @param text 남길 글. 없으면 null(사진만)
 * @param uploadTickets 올린 사진의 업로드 표(06 4-0 업로드). 없으면 빈 목록
 * @param asksCheck [상황이 바뀌었어요]로 상대에게 확인을 요청하는지. 아직 만들지 않아(#219 다음 PR) true면 {@code
 *     VALIDATION_FAILED}다
 */
public record ProgressNoteRequest(String text, List<String> uploadTickets, Boolean asksCheck) {

  public ProgressNoteRequest {
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }

  /** 글이나 사진이 하나라도 있는지. 값이 있는지만 보고, 업로드 표 검사는 사진을 붙이는 단계가 맡는다. */
  public boolean hasEvidence() {
    return (text != null && !text.isBlank()) || !uploadTickets.isEmpty();
  }
}
