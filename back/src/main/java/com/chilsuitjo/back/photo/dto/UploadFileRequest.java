package com.chilsuitjo.back.photo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 업로드 표를 받을 사진 한 장.
 *
 * @param originalBytes 업로드할 원본 파일 크기(바이트)
 * @param thumbBytes 업로드할 썸네일 파일 크기(바이트)
 * @param originalSha256 원본 파일의 SHA-256 지문
 * @param thumbSha256 썸네일 파일의 SHA-256 지문
 * @param deviceTakenAt 브라우저가 사진을 다시 그리기 전에 읽은 촬영 시각(03 6-1, 06 PHO-1f). 서버가 확인할 수 없는 참고값이라 업로드 표
 *     서명에 넣어 두고 {@code attach}에서 꺼낸다
 */
public record UploadFileRequest(
    @Positive long originalBytes,
    @Positive long thumbBytes,
    @NotBlank String originalSha256,
    @NotBlank String thumbSha256,
    Instant deviceTakenAt) {}
