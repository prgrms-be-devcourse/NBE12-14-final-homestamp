package com.chilsuitjo.back.photo.dto;

import java.time.Instant;

/**
 * 사진 한 장의 업로드 자리.
 *
 * @param index 요청 목록에서의 순서
 * @param ticket 업로드 표. 사진이 붙는 행동의 본문에 넣는다
 * @param ticketExpiresAt 업로드 표 만료(12시간)
 * @param original 원본 파일을 올릴 주소와 폼 필드
 * @param thumb 썸네일 파일을 올릴 주소와 폼 필드
 */
public record UploadSlotResponse(
    int index,
    String ticket,
    Instant ticketExpiresAt,
    PresignedPostResponse original,
    PresignedPostResponse thumb) {}
