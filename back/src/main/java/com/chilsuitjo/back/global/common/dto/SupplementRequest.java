package com.chilsuitjo.back.global.common.dto;

import java.util.List;

/**
 * 보충하고 다시 확인 요청하기. 모든 {@code /supplement} API(수리 건, 점검 항목, 지불)가 같은 모양을 쓴다(06 4장). 사진·설명 중 하나 이상이
 * 없으면 {@code EVIDENCE_REQUIRED}, 한 기록에 7일에 한 번(그 안이면 {@code ASKED_RECENTLY}).
 *
 * @param description 보충 설명. 없으면 null
 * @param uploadTickets 올린 사진의 업로드 표(06 4-0 업로드). 없으면 빈 목록
 */
public record SupplementRequest(String description, List<String> uploadTickets) {

  public SupplementRequest {
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }

  /**
   * 사진이나 설명이 하나라도 있는지. false면 부르는 쪽이 {@code EVIDENCE_REQUIRED}로 거절한다.
   *
   * <p>값이 있는지만 본다. 업로드 표의 서명·회원·호실·만료 확인과 사진 붙이기 성공은 사진을 붙이는 단계({@code PhotoService.attach}, 06 4-0
   * 업로드)가 맡는다.
   */
  public boolean hasEvidence() {
    return (description != null && !description.isBlank()) || !uploadTickets.isEmpty();
  }
}
