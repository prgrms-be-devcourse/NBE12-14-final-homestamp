package com.chilsuitjo.back.photo.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 링크 열람용 사진. 링크에서 숨긴 사진은 빠지고, 5분 주소를 함께 준다. link의 {@code LinkRecordResponse.Photo}로 옮기는 일은 link가
 * 한다({@code erased}를 그쪽의 {@code erased}·{@code erasedAt}으로).
 *
 * @param id 사진 ID
 * @param target 사진이 붙은 대상
 * @param sortOrder 같은 대상 안에서의 표시 순서
 * @param thumbUrl 썸네일 열람 주소. 지운 사진이면 null
 * @param originalUrl 원본 열람 주소. 지운 사진이면 null
 * @param expiresAt 주소 만료({@code photo.url.short}, 자격 증명 만료 − 10분 이하). 지운 사진이면 null
 * @param erased 지운 사진이면 흔적. 이때 주소 둘은 null
 */
public record LinkPhoto(
    UUID id,
    PhotoTarget target,
    int sortOrder,
    String thumbUrl,
    String originalUrl,
    Instant expiresAt,
    ErasedPhoto erased) {}
