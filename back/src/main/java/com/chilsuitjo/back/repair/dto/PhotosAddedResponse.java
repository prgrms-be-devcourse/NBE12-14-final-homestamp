package com.chilsuitjo.back.repair.dto;

import java.util.List;
import java.util.UUID;

/**
 * 사진 추가의 응답 본문({@code POST /api/repair-cases/{id}/photos}).
 *
 * @param photoIds 붙인 사진(업로드 표 순서)
 */
public record PhotosAddedResponse(List<UUID> photoIds) {}
