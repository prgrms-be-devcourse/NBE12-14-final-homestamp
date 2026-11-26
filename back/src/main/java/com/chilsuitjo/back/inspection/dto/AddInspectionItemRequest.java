package com.chilsuitjo.back.inspection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 점검 항목 더하기 요청(06 4-7 {@code POST /api/inspections/{id}/items}). 항목은 사용자가 적은 하자 하나다(부위 + 설명 + 사진,
 * 03 H "항목"). 더한 측·잠긴 뒤 추가·거주 종료 뒤 추가는 서버가 정한다.
 *
 * <p>설명은 앞뒤 공백을 자른다. 설명에 주민등록번호 형태가 있으면 서비스가 {@code RESIDENT_ID_BLOCKED}로 막는다. 사진이 없으면 빈 목록이다.
 *
 * @param roomPartId 그 호실의 부위. 남의 부위·없는 부위는 {@code NOT_FOUND}, 숨긴 부위는 {@code VALIDATION_FAILED}
 * @param description 하자 설명(필수, 최대 2,000자)
 * @param uploadTickets 올린 사진의 업로드 표 0~10개(06 4-0 업로드). 목록 위치(0부터)가 사진 번호({@code photoIndex})다
 */
public record AddInspectionItemRequest(
    @NotNull UUID roomPartId,
    @NotBlank @Size(max = 2000) String description,
    @Size(max = 10) List<@NotBlank String> uploadTickets) {

  public AddInspectionItemRequest {
    description = description == null ? null : description.strip();
    // List.copyOf는 null 요소에서 검증 전에 터지므로(500) 그대로 두고 @NotBlank가 400으로 막게 한다
    uploadTickets = uploadTickets == null ? List.of() : uploadTickets;
  }
}
