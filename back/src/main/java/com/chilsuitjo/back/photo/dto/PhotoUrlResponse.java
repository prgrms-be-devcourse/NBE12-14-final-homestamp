package com.chilsuitjo.back.photo.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 사진 하나의 주소.
 *
 * @param photoId 사진 ID
 * @param url 요청한 종류의 사진 열람 주소. 지운 사진이면 null
 * @param expiresAt 열람 주소의 만료 시각. 지운 사진이면 null
 * @param erased 지운 사진이면 흔적. 이때 주소는 null(불변식 16, 지운 사진에는 어떤 주소도 발급하지 않음)
 */
public record PhotoUrlResponse(UUID photoId, String url, Instant expiresAt, ErasedPhoto erased) {}
