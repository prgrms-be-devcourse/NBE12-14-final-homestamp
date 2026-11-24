package com.chilsuitjo.back.photo.dto;

import java.util.List;

/**
 * 업로드 표 받기 응답. 요청한 사진마다 한 칸.
 *
 * @param slots 사진별 업로드 표와 원본·썸네일 업로드 정보
 */
public record UploadTicketsResponse(List<UploadSlotResponse> slots) {}
