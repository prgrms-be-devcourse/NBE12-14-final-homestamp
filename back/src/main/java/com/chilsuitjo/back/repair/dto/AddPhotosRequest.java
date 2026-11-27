package com.chilsuitjo.back.repair.dto;

import java.util.List;

/**
 * 사진 추가(06 4-6 {@code POST /api/repair-cases/{id}/photos}, REP-7g). 업로드 표 1~10개가 필요하고, 그 밖이면 {@code
 * VALIDATION_FAILED}다. 사진 단계는 화면이 보내지 않고 서버가 수리 진행으로 정한다(05 2-8, {@code PhotoStage}).
 *
 * @param uploadTickets 올린 사진의 업로드 표(06 4-0 업로드). 없으면 빈 목록
 */
public record AddPhotosRequest(List<String> uploadTickets) {

  public AddPhotosRequest {
    // List.copyOf는 null 요소에서 터지므로(500) 그대로 두고 서비스가 400으로 막는다
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }
}
