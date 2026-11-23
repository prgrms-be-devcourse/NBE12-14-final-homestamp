package com.chilsuitjo.back.repair.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 하자 등록 요청(06 4-6 {@code POST /api/rooms/{id}/repair-cases}, REP-1d). 부위·제목은 필수, 설명은 선택이다. 빠지거나 길면
 * {@code VALIDATION_FAILED}(400). 제목·설명에 주민등록번호 형태가 있으면 서비스가 {@code RESIDENT_ID_BLOCKED}로 막는다.
 *
 * <p>검사 전에 값을 다듬는다: 제목은 앞뒤 공백을 자르고, 설명은 비었거나 공백뿐이면 없는 것(NULL)으로 본다. 설명 안의 공백·줄바꿈은 그대로 둔다.
 *
 * <p>사진(업로드 표 1~10개, 필수)과 알리기는 아직 받지 않는다. 사진 PR에서 {@code uploadTickets}를 진짜 칸으로 바꾸고 {@code
 * PHOTO_REQUIRED}로 막는다(#134). 그 전에 화면이 사진을 보내면 조용히 버리지 않고 {@code VALIDATION_FAILED}로 거절한다.
 *
 * @param roomPartId 그 호실의 부위. 남의 부위·없는 부위는 {@code NOT_FOUND}, 숨긴 부위는 {@code VALIDATION_FAILED}
 * @param title 제목(V1 {@code repair_content.title} 100자)
 * @param description 설명. 없어도 된다. 최대 2,000자(06 4-6)
 * @param uploadTickets 아직 받지 않는 칸. 값이 오면 거절한다
 */
public record RegisterRepairRequest(
    @NotNull UUID roomPartId,
    @NotBlank @Size(max = 100) String title,
    @Size(max = 2000) String description,
    @Null Object uploadTickets) {

  public RegisterRepairRequest {
    title = title == null ? null : title.strip();
    description = description == null || description.isBlank() ? null : description;
  }
}
