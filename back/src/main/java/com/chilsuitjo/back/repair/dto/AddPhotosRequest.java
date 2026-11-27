package com.chilsuitjo.back.repair.dto;

import com.chilsuitjo.back.photo.rule.PhotoStage;
import java.util.List;

/**
 * 사진 추가(06 4-6 {@code POST /api/repair-cases/{id}/photos}, REP-7g). 업로드 표 1~10개와 단계({@code
 * BEFORE}·{@code AFTER})가 필요하다. 그 밖이면 {@code VALIDATION_FAILED}다.
 *
 * @param uploadTickets 올린 사진의 업로드 표(06 4-0 업로드). 없으면 빈 목록
 * @param stage 사진 단계. 수리 전 {@code BEFORE}, 수리 후 {@code AFTER}
 */
public record AddPhotosRequest(List<String> uploadTickets, PhotoStage stage) {

  public AddPhotosRequest {
    // List.copyOf는 null 요소에서 터지므로(500) 그대로 두고 서비스가 400으로 막는다
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }
}
