package com.chilsuitjo.back.repair.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 하자 등록 요청(06 4-6 {@code POST /api/rooms/{id}/repair-cases}, REP-1d). 부위·제목은 필수, 설명은 선택이다. 빠지면
 * {@code VALIDATION_FAILED}(400).
 *
 * <p>사진(업로드 표 1~10개, 필수)과 알리기는 아직 받지 않는다. 사진 PR에서 {@code uploadTickets}를 더하고 {@code
 * PHOTO_REQUIRED}로 막는다(#134).
 *
 * @param roomPartId 그 호실의 부위. 남의 부위·없는 부위는 {@code NOT_FOUND}, 숨긴 부위는 {@code VALIDATION_FAILED}
 * @param title 제목(V1 {@code repair_content.title} 100자)
 * @param description 설명. 없어도 된다
 */
public record RegisterRepairRequest(
    @NotNull UUID roomPartId, @NotBlank @Size(max = 100) String title, String description) {}
