package com.chilsuitjo.back.photo.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 개인정보 사진 지우기 응답.
 *
 * @param photoId 지운 원본 사진 ID
 * @param erasedAt 파일을 지운 시각
 * @param redactedPhotoId 가린 사본을 함께 올렸으면 그 사진. 아니면 null
 */
public record ErasePhotoResponse(UUID photoId, Instant erasedAt, UUID redactedPhotoId) {}
